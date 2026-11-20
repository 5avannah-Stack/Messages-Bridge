package instagram

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/useragent"
)

// The events Kotlin reads (InstagramConnector.kt, through GoEvents.kt), by kind. Chats and messages have the same
// shape as WhatsApp's and Signal's:
//
//	state   {state: connected|disconnected|logged_out|error, reason}
//	chat    chat                    a chat's details; unread -1 keeps the count
//	message message
//	media   media                   a message's media is saved, or failed
//	read    {chat, id}              read up to id on another of the person's devices
//
// A chat's id is Instagram's thread id, the one its events name; a message's id is Instagram's message id.

type state struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
}

type chat struct {
	ID     string `json:"id"`
	Name   string `json:"name,omitempty"`
	Handle string `json:"handle,omitempty"`
	Group  bool   `json:"group"`
	LastMs int64  `json:"lastMs"`
	Unread int    `json:"unread"`
}

// message's Kind is "text", "image", "video", "voice", "sticker" or "other". Media arrives later as a "media" event
// with the same ID.
type message struct {
	ID        string `json:"id"`
	Chat      string `json:"chat"`
	Sender    string `json:"sender"`
	FromMe    bool   `json:"fromMe"`
	Timestamp int64  `json:"timestamp"`
	PushName  string `json:"pushName,omitempty"`
	Kind      string `json:"kind"`
	Text      string `json:"text,omitempty"`
	Mime      string `json:"mime,omitempty"`
	Seconds   uint32 `json:"seconds,omitempty"`
	Media     bool   `json:"media"`
	History   bool   `json:"history"`
	ReplyTo   string `json:"replyTo,omitempty"`
}

type media struct {
	ID    string `json:"id"`
	Chat  string `json:"chat"`
	Path  string `json:"path,omitempty"`
	Error string `json:"error,omitempty"`
}

type read struct {
	Chat string `json:"chat"`
	ID   string `json:"id"`
}

// handle turns instameow's events into the few the app needs. An error makes instameow retry the event, so only a
// failure to keep the client's position returns one.
func (b *Bridge) handle(ctx context.Context, evt slidetypes.ClientEvent) error {
	switch v := evt.(type) {
	case *slidetypes.Connected:
		b.emit("state", state{State: "connected"})
	case *slidetypes.Disconnected:
		b.emit("state", state{State: stateOf(v.Error), Reason: errText(v.Error)})
	case *slidetypes.AuthError:
		b.emit("state", state{State: "logged_out", Reason: errText(v.Error)})
	case *slidetypes.SeqIDUpdate:
		b.setMeta(metaSeqID, strconv.FormatInt(v.SeqID, 10))
	case *slidetypes.ReconnectionStateUpdate:
		b.setMeta(metaState, string(v.State))
	case *slidetypes.ResnapshotRequired:
		// Instagram lost track of this client's position: start again from the inbox, with a fresh client.
		b.setMeta(metaSeqID, "")
		go func() {
			b.Disconnect()
			b.mu.Lock()
			b.cli = nil
			b.mu.Unlock()
			_ = b.Connect()
		}()
	case *slidetypes.Delta:
		b.onDelta(ctx, v)
	}
	return nil
}

// stateOf names a lost connection: a rejected session is a logout, anything else a disconnect the hub retries.
func stateOf(err error) string {
	if errors.Is(err, httpclient.ErrTokenInvalidated) || errors.Is(err, httpclient.ErrAccountSuspended) ||
		errors.Is(err, httpclient.ErrCheckpointRequired) || errors.Is(err, httpclient.ErrChallengeRequired) {
		return "logged_out"
	}
	return "disconnected"
}

func (b *Bridge) onDelta(ctx context.Context, d *slidetypes.Delta) {
	switch v := d.Data.(type) {
	case *slidetypes.NewMessageEvent:
		if v.Message == nil {
			return
		}
		threadID := d.ThreadIGID
		if threadID == "" {
			threadID = v.Message.ThreadFBID
		}
		b.onMessage(ctx, threadID, v.Message)
	case *slidetypes.MarkReadEvent:
		// The person read the thread on another device or in Instagram.
		if last := b.thread(d.ThreadIGID).lastMsg; last != "" {
			b.emit("read", read{Chat: d.ThreadIGID, ID: last})
		}
	}
}

// onMessage reports one message, after the chat it's in when the bridge hasn't seen that chat yet. Edits, unsends,
// reactions, group changes and Instagram's notices aren't in this version.
func (b *Bridge) onMessage(ctx context.Context, threadID string, msg *slidetypes.Message) {
	if threadID == "" {
		return
	}
	cli, self := b.client()
	m, url := describe(msg, threadID, self)
	if m.Kind == "" {
		return
	}
	t := b.thread(threadID)
	if t.key == 0 && cli != nil {
		// A new chat: fetch who it's with, which also gives the ids sending and read receipts need.
		ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		if resp, err := cli.GetThread(ctx, slidetypes.MakeGetThreadInfoRequest(threadID)); err == nil && resp.ThreadInfo.AsIGDirectThread != nil {
			b.onThread(resp.ThreadInfo.AsIGDirectThread)
		}
		cancel()
	}
	m.Media = url != ""
	b.setLastMessage(threadID, m.ID)
	b.emit("chat", chat{ID: threadID, LastMs: m.Timestamp, Unread: -1})
	b.emit("message", m)
	if url != "" {
		go b.fetchMedia(m, url)
	}
}

// describe reads an Instagram message into the app's message and the URL of its media, if any. A Kind of "" means
// the message isn't shown.
func describe(msg *slidetypes.Message, threadID string, self int64) (message, string) {
	m := message{
		ID:        msg.ID,
		Chat:      threadID,
		Sender:    strconv.FormatInt(msg.SenderFBID, 10),
		FromMe:    msg.SenderFBID == self && self != 0,
		Timestamp: msg.TimestampMS.UnixMilli(),
		ReplyTo:   msg.RepliedToMessageID,
	}
	if s := msg.Sender; s != nil && !m.FromMe {
		m.PushName = s.UserDict.FullName
		if m.PushName == "" {
			m.PushName = s.Name
		}
	}
	switch c := msg.Content.Content.(type) {
	case *slidetypes.MessageContentText:
		m.Kind, m.Text = "text", c.TextBody
		if m.Text == "" {
			m.Text = msg.TextBody
		}
	case *slidetypes.MessageContentImage:
		if a := first(c.Attachments); a != nil {
			m.Kind, m.Mime = "image", "image/jpeg"
			return m, cdn(a)
		}
	case *slidetypes.MessageContentMultiMedia:
		// An album: its first photo or video, and a line saying there is more.
		if a := first(c.Attachments); a != nil {
			m.Kind, m.Mime = "image", "image/jpeg"
			if a.DashManifest != "" || strings.Contains(strings.ToLower(a.Typename), "video") {
				m.Kind, m.Mime = "video", "video/mp4"
			}
			if len(c.Attachments) > 1 {
				m.Text = fmt.Sprintf("+%d more in Instagram", len(c.Attachments)-1)
			}
			return m, cdn(a)
		}
	case *slidetypes.MessageContentVideo:
		if a := first(c.Videos); a != nil {
			m.Kind, m.Mime = "video", "video/mp4"
			return m, cdn(a)
		}
	case *slidetypes.MessageContentAudio:
		if len(c.AudioAttachments) > 0 && c.AudioAttachments[0] != nil {
			a := c.AudioAttachments[0]
			m.Kind, m.Mime, m.Seconds = "voice", "audio/mp4", uint32((a.PlayableDurationMS+999)/1000)
			return m, a.AttachmentCDNURL
		}
	case *slidetypes.MessageContentAnimatedMedia:
		if len(c.AnimatedMedia) > 0 && c.AnimatedMedia[0] != nil {
			a := c.AnimatedMedia[0]
			m.Kind, m.Mime = "image", "image/webp"
			if a.IsSticker {
				m.Kind = "sticker"
			}
			return m, a.AttachmentWebpURL
		}
	case *slidetypes.MessageContentSticker:
		m.Kind, m.Mime, m.Text = "sticker", "image/jpeg", c.AltText
		return m, c.PreviewURL
	case *slidetypes.MessageContentRavenImage, *slidetypes.MessageContentRavenVideo:
		// View-once and replayable photos and videos stay in Instagram, which counts the views.
		m.Kind, m.Text = "other", "Photo or video to view in Instagram"
	case *slidetypes.MessageContentXMA:
		// A shared post, reel, story or link.
		m.Kind, m.Text = "other", xmaText(c)
	}
	return m, ""
}

func first(atts []*slidetypes.Attachment) *slidetypes.Attachment {
	if len(atts) == 0 {
		return nil
	}
	return atts[0]
}

func cdn(a *slidetypes.Attachment) string {
	if a.AttachmentCDNURL != "" {
		return a.AttachmentCDNURL
	}
	return a.AttachmentCDNFallbackURL
}

func xmaText(c *slidetypes.MessageContentXMA) string {
	var parts []string
	if c.XMATextBody != "" {
		parts = append(parts, c.XMATextBody)
	}
	if x := c.XMA; x != nil {
		for _, s := range []string{x.TitleText, x.XMATitle, x.CaptionBodyText} {
			if s != "" {
				parts = append(parts, s)
				break
			}
		}
		if x.TargetURL != "" {
			parts = append(parts, x.TargetURL)
		}
	}
	if len(parts) == 0 {
		return "Shared post"
	}
	return strings.Join(parts, "\n")
}

// onMailbox reports the chats of the inbox's first page, newest first, without their messages: the inbox carries only
// stubs of those.
func (b *Bridge) onMailbox(mb *slidetypes.Mailbox) {
	if mb == nil {
		return
	}
	for _, edge := range mb.ThreadsByFolder.Edges {
		if t := edge.Node.AsIGDirectThread; t != nil {
			b.onThread(t)
		}
	}
}

// onThread keeps a thread's ids and reports it as a chat: a group by its title, a person by their name and handle.
func (b *Bridge) onThread(t *slidetypes.ThreadInfo) {
	if t.ID == "" {
		return
	}
	_, _ = b.db.Exec(`INSERT INTO bridge_thread (id, long_id, key) VALUES (?, ?, ?)
		ON CONFLICT (id) DO UPDATE SET long_id = excluded.long_id, key = excluded.key`, t.ID, t.ThreadID, t.ThreadKey)
	c := chat{ID: t.ID, Group: t.IsGroup || len(t.Users) > 1, LastMs: t.LastActivityTimestampMS.UnixMilli(), Unread: -1}
	if c.Group {
		c.Name = t.ThreadTitle
	} else if len(t.Users) == 1 && t.Users[0] != nil {
		c.Name, c.Handle = t.Users[0].FullName, t.Users[0].Username
		if c.Name == "" {
			c.Name = t.Users[0].Username
		}
	}
	if t.MarkedAsUnread {
		c.Unread = 1
	}
	b.emit("chat", c)
}

type threadIDs struct {
	longID, lastMsg string
	key             int64
}

func (b *Bridge) thread(id string) threadIDs {
	var t threadIDs
	_ = b.db.QueryRow(`SELECT long_id, key, last_msg FROM bridge_thread WHERE id = ?`, id).Scan(&t.longID, &t.key, &t.lastMsg)
	return t
}

func (b *Bridge) setLastMessage(threadID, msgID string) {
	_, _ = b.db.Exec(`INSERT INTO bridge_thread (id, last_msg) VALUES (?, ?)
		ON CONFLICT (id) DO UPDATE SET last_msg = excluded.last_msg`, threadID, msgID)
}

// download fetches media from Instagram's CDN with a browser's headers, as mautrix-meta does; tests replace it.
var download = func(ctx context.Context, url, mime string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Sec-Fetch-Mode", "no-cors")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	req.Header.Set("User-Agent", useragent.UserAgent)
	req.Header.Set("sec-ch-ua", useragent.SecCHUserAgent)
	req.Header.Set("sec-ch-ua-platform", useragent.SecCHPlatform)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("media: HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, maxMedia+1))
}

const maxMedia = 100 << 20

func (b *Bridge) fetchMedia(m message, url string) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	out := media{ID: m.ID, Chat: m.Chat}
	data, err := download(ctx, url, m.Mime)
	if err == nil {
		// Message ids come off the network, so the name is cleaned.
		name := strings.Map(func(r rune) rune {
			if r == '/' || r == '\\' || r == '.' {
				return '_'
			}
			return r
		}, m.ID) + extension(m.Mime)
		out.Path = filepath.Join(b.mediaDir, name)
		err = os.WriteFile(out.Path, data, 0o600)
	}
	if err != nil {
		out.Path, out.Error = "", err.Error()
	}
	b.emit("media", out)
}

func extension(mime string) string {
	switch mime {
	case "image/jpeg":
		return ".jpg"
	case "image/webp":
		return ".webp"
	case "video/mp4":
		return ".mp4"
	case "audio/mp4":
		return ".m4a"
	}
	return ".bin"
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func (b *Bridge) emit(kind string, payload any) {
	if b.listener == nil {
		return
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	b.listener.OnEvent(kind, string(data))
}
