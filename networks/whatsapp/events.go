package whatsapp

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// The events Kotlin reads (WhatsAppEvents.kt), by kind:
//
//	state       {state: connected|disconnected|logged_out|replaced, reason}
//	paired      {account}               a login succeeded
//	pair_failed {error}
//	qr          {code, timeoutMs}       a renewed QR code
//	qr_done     {result}                the QR login ended without success
//	chat        chat                    a chat's details; unread -1 keeps the count
//	message     message
//	media       media                   a message's media is saved, or failed
//	read        {chat, id}              read up to id on another of the person's devices

type state struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
}

type paired struct {
	Account string `json:"account"`
}

type pairFailed struct {
	Error string `json:"error"`
}

type qr struct {
	Code      string `json:"code"`
	TimeoutMs int64  `json:"timeoutMs"`
}

type qrDone struct {
	Result string `json:"result"`
}

type chat struct {
	JID    string `json:"jid"`
	Name   string `json:"name,omitempty"`
	Phone  string `json:"phone,omitempty"` // digits, when WhatsApp has told us the number behind a LID
	Group  bool   `json:"group"`
	LastMs int64  `json:"lastMs"`
	Unread int    `json:"unread"`
}

// message's Kind is "text", "image", "video", "audio", "voice", "file", "sticker" or "other". Live media arrives
// later as a "media" event with the same ID; history media isn't downloaded (Media is false).
type message struct {
	ID          string `json:"id"`
	Chat        string `json:"chat"`
	Sender      string `json:"sender"`
	SenderPhone string `json:"senderPhone,omitempty"`
	FromMe      bool   `json:"fromMe"`
	Timestamp   int64  `json:"timestamp"`
	PushName    string `json:"pushName,omitempty"`
	Kind        string `json:"kind"`
	Text        string `json:"text,omitempty"`
	Mime        string `json:"mime,omitempty"`
	FileName    string `json:"fileName,omitempty"`
	Size        uint64 `json:"size,omitempty"`
	Seconds     uint32 `json:"seconds,omitempty"`
	Media       bool   `json:"media"`
	History     bool   `json:"history"`
	ReplyTo     string `json:"replyTo,omitempty"`
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

// handle turns whatsmeow's events into the few the app needs.
func (b *Bridge) handle(evt any) {
	switch v := evt.(type) {
	case *events.QR:
		b.mu.Lock()
		if b.qrSeen != nil {
			close(b.qrSeen)
			b.qrSeen = nil
		}
		b.mu.Unlock()
	case *events.PairSuccess:
		b.emit("paired", paired{Account: v.ID.User})
	case *events.PairError:
		b.emit("pair_failed", pairFailed{Error: v.Error.Error()})
	case *events.Connected:
		b.emit("state", state{State: "connected"})
	case *events.Disconnected:
		b.emit("state", state{State: "disconnected"})
	case *events.LoggedOut:
		b.emit("state", state{State: "logged_out", Reason: v.Reason.String()})
	case *events.StreamReplaced:
		// Another client took over this device's session; reconnecting would just fight it.
		b.emit("state", state{State: "replaced"})
	case *events.Message:
		b.onMessage(v, false)
	case *events.Receipt:
		if v.IsFromMe && (v.Type == types.ReceiptTypeRead || v.Type == types.ReceiptTypeReadSelf) && len(v.MessageIDs) > 0 {
			b.emit("read", read{Chat: v.Chat.String(), ID: v.MessageIDs[len(v.MessageIDs)-1]})
		}
	case *events.HistorySync:
		b.onHistory(v.Data.GetConversations())
	}
}

func (b *Bridge) onMessage(v *events.Message, history bool) {
	m, downloadable := b.describe(v)
	if m.Kind == "other" && m.Text == "" {
		// Reactions, edits, deletes, protocol messages: not in this version.
		return
	}
	m.History = history
	if !history && !m.FromMe && !v.Info.IsGroup {
		// A person's chat takes their name as soon as they write.
		if name := b.nameOf(v.Info.Chat); name != "" || v.Info.PushName != "" {
			if name == "" {
				name = v.Info.PushName
			}
			b.emit("chat", chat{JID: m.Chat, Name: name, Phone: b.phoneOf(context.Background(), v.Info.Chat), LastMs: m.Timestamp, Unread: -1})
		}
	}
	m.Media = downloadable != nil && !history
	b.emit("message", m)
	if m.Media {
		go b.fetchMedia(m, downloadable)
	}
}

// onHistory stores the chats a history sync carries, with their messages. History replaces the unread count.
func (b *Bridge) onHistory(conversations []*waHistorySync.Conversation) {
	for _, c := range conversations {
		jid, err := types.ParseJID(c.GetID())
		if err != nil || c.GetID() == "" {
			continue
		}
		name := c.GetName()
		if name == "" {
			name = c.GetDisplayName()
		}
		if name == "" && jid.Server != types.GroupServer {
			name = b.nameOf(jid)
		}
		b.emit("chat", chat{
			JID:    jid.String(),
			Name:   name,
			Phone:  b.phoneOf(context.Background(), jid),
			Group:  jid.Server == types.GroupServer,
			LastMs: int64(c.GetConversationTimestamp()) * 1000,
			Unread: int(c.GetUnreadCount()),
		})
		for _, hm := range c.GetMessages() {
			web := hm.GetMessage()
			if web == nil {
				continue
			}
			evt, err := b.cli.ParseWebMessage(jid, web)
			if err != nil {
				continue
			}
			b.onMessage(evt, true)
		}
	}
}

// describe reads one message's content; it returns the media to download, if any.
func (b *Bridge) describe(v *events.Message) (message, whatsmeow.DownloadableMessage) {
	m := message{
		ID:          v.Info.ID,
		Chat:        v.Info.Chat.String(),
		Sender:      v.Info.Sender.String(),
		SenderPhone: b.phoneOf(context.Background(), v.Info.Sender),
		FromMe:      v.Info.IsFromMe,
		Timestamp:   v.Info.Timestamp.UnixMilli(),
		PushName:    v.Info.PushName,
		Kind:        "other",
	}
	msg := v.Message
	if ctx := contextInfo(msg); ctx != nil {
		m.ReplyTo = ctx.GetStanzaID()
	}
	switch {
	case msg.GetConversation() != "":
		m.Kind, m.Text = "text", msg.GetConversation()
	case msg.GetExtendedTextMessage() != nil:
		m.Kind, m.Text = "text", msg.GetExtendedTextMessage().GetText()
	case msg.GetImageMessage() != nil:
		x := msg.GetImageMessage()
		m.Kind, m.Text, m.Mime, m.Size = "image", x.GetCaption(), x.GetMimetype(), x.GetFileLength()
		return m, x
	case msg.GetVideoMessage() != nil:
		x := msg.GetVideoMessage()
		m.Kind, m.Text, m.Mime, m.Size, m.Seconds = "video", x.GetCaption(), x.GetMimetype(), x.GetFileLength(), x.GetSeconds()
		return m, x
	case msg.GetAudioMessage() != nil:
		x := msg.GetAudioMessage()
		m.Kind, m.Mime, m.Size, m.Seconds = "audio", x.GetMimetype(), x.GetFileLength(), x.GetSeconds()
		if x.GetPTT() {
			m.Kind = "voice"
		}
		return m, x
	case msg.GetDocumentMessage() != nil:
		x := msg.GetDocumentMessage()
		m.Kind, m.Text, m.Mime, m.Size, m.FileName = "file", x.GetCaption(), x.GetMimetype(), x.GetFileLength(), x.GetFileName()
		return m, x
	case msg.GetStickerMessage() != nil:
		x := msg.GetStickerMessage()
		m.Kind, m.Mime, m.Size = "sticker", x.GetMimetype(), x.GetFileLength()
		return m, x
	}
	return m, nil
}

func contextInfo(msg *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case msg.GetExtendedTextMessage() != nil:
		return msg.GetExtendedTextMessage().GetContextInfo()
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetContextInfo()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetContextInfo()
	case msg.GetAudioMessage() != nil:
		return msg.GetAudioMessage().GetContextInfo()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetContextInfo()
	}
	return nil
}

func (b *Bridge) fetchMedia(m message, d whatsmeow.DownloadableMessage) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	out := media{ID: m.ID, Chat: m.Chat}
	data, err := b.download(ctx, d)
	if err == nil {
		// Message IDs are WhatsApp's hex strings, but the name is cleaned anyway since it comes off the network.
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

// Go's mime table doesn't know Opus or most of WhatsApp's types, so the few it sends are spelled out.
func extension(mime string) string {
	base, _, _ := strings.Cut(mime, ";")
	switch strings.TrimSpace(base) {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "video/mp4":
		return ".mp4"
	case "audio/ogg":
		return ".ogg"
	case "audio/mp4", "audio/aac":
		return ".m4a"
	case "audio/mpeg":
		return ".mp3"
	case "application/pdf":
		return ".pdf"
	}
	return ".bin"
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
