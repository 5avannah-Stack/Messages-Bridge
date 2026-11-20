package signalbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
)

// The events Kotlin reads (SignalConnector.kt, through GoEvents.kt), by kind. Chats and messages have the same shape
// as WhatsApp's:
//
//	state   {state: connected|disconnected|logged_out|error, reason}
//	paired  {account, number}       a link succeeded
//	qr      {code, timeoutMs}       a renewed link to show as a QR code
//	qr_done {result}                linking ended without success
//	chat    chat                    a chat's details; unread -1 keeps the count
//	message message
//	media   media                   a message's media is saved, or failed
//	read    {chat, id}              read up to id on another of the person's devices
//
// A person's chat id is their ACI (a UUID), a group's its 44-character base64 identifier. A message id is
// "<sender ACI>|<sent timestamp>", Signal's own way to name a message, with "|<n>" for the second and later
// attachments of one Signal message, which arrive as messages of their own.

type state struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
}

type paired struct {
	Account string `json:"account"`
	Number  string `json:"number,omitempty"`
}

type qr struct {
	Code      string `json:"code"`
	TimeoutMs int64  `json:"timeoutMs"`
}

type qrDone struct {
	Result string `json:"result"`
}

type chat struct {
	ID     string `json:"id"`
	Name   string `json:"name,omitempty"`
	Phone  string `json:"phone,omitempty"`
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

// longText is how Signal carries a body over 2 KiB: the start inline, the whole text as an attachment.
const longText = "text/x-signal-plain"

// download, contact and groupTitle reach Signal's servers (or signalmeow's caches of them); tests replace them.
var (
	download = func(ctx context.Context, a *signalpb.AttachmentPointer) ([]byte, error) {
		return signalmeow.DownloadAttachmentWithPointer(ctx, a, nil, nil)
	}
	contact = func(ctx context.Context, cli *signalmeow.Client, aci uuid.UUID) (*types.Recipient, error) {
		return cli.ContactByACI(ctx, aci)
	}
	groupTitle = func(ctx context.Context, cli *signalmeow.Client, gid types.GroupIdentifier) (string, error) {
		g, _, err := cli.RetrieveGroupByID(ctx, gid, 0)
		if err != nil || g == nil {
			return "", err
		}
		return g.Title, nil
	}
)

// handle turns signalmeow's events into the few the app needs. Returning true tells signalmeow the event is handled;
// every event here is, since the app stores what it gets.
func (b *Bridge) handle(evt events.SignalEvent) bool {
	cli := b.client()
	if cli == nil {
		return true
	}
	switch v := evt.(type) {
	case *events.ChatEvent:
		if dm, ok := v.Event.(*signalpb.DataMessage); ok {
			b.onMessage(cli, v.Info, dm)
		}
	case *events.ReadSelf:
		b.readElsewhere(v.Messages)
	case *events.LoggedOut:
		b.emit("state", state{State: "logged_out", Reason: errText(v.Error)})
	}
	return true
}

// onMessage reports one Signal message. Reactions, edits, deletes, calls and group changes aren't in this version;
// a group change still refreshes the group's name.
func (b *Bridge) onMessage(cli *signalmeow.Client, info events.MessageInfo, dm *signalpb.DataMessage) {
	ctx := context.Background()
	fromMe := info.Sender == cli.Store.ACI
	ts := dm.GetTimestamp()
	group := dm.GetGroupV2() != nil
	msgs := describe(info.Sender, info.ChatID, dm)
	if len(msgs) == 0 {
		if group && len(dm.GetGroupV2().GetGroupChange()) > 0 {
			b.emit("chat", b.chatOf(ctx, cli, info.ChatID, true, 0))
		}
		return
	}
	b.emit("chat", b.chatOf(ctx, cli, info.ChatID, group, int64(ts)))
	senderName, senderPhone := "", ""
	if !fromMe {
		if r, err := contact(ctx, cli, info.Sender); err == nil && r != nil {
			senderName, senderPhone = nameOf(r), digits(r.E164)
		}
		b.addUnread(ctx, info.ChatID, info.Sender, ts, msgs[len(msgs)-1].ID)
	}
	for i, m := range msgs {
		m.FromMe, m.PushName, m.SenderPhone = fromMe, senderName, senderPhone
		att := attachmentOf(dm, i)
		if att != nil && att.GetContentType() == longText {
			if data, err := fetch(att); err == nil {
				m.Text = string(data)
			}
			att = nil
		}
		m.Media = att != nil
		b.emit("message", m)
		if m.Media {
			go b.fetchMedia(m, att)
		}
	}
}

// describe reads a Signal message into the app's messages: one per attachment, the text with the first, or one with
// only the text. The long-text attachment, when there is one, stays with the text.
func describe(sender uuid.UUID, chatID string, dm *signalpb.DataMessage) []message {
	if dm.Reaction != nil || dm.Delete != nil || dm.GetFlags() != 0 || dm.GroupCallUpdate != nil {
		return nil
	}
	base := message{
		ID:        messageID(sender, dm.GetTimestamp()),
		Chat:      chatID,
		Sender:    sender.String(),
		Timestamp: int64(dm.GetTimestamp()),
		Text:      dm.GetBody(),
		Kind:      "text",
	}
	if q := dm.GetQuote(); q != nil {
		if author, err := signalmeow.ParseStringOrBinaryUUID(q.GetAuthorAci(), q.GetAuthorAciBinary()); err == nil {
			base.ReplyTo = messageID(author, q.GetId())
		}
	}
	atts := attachments(dm)
	if len(atts) == 0 {
		switch {
		case base.Text != "":
			return []message{base}
		case dm.GetContact() != nil:
			base.Kind, base.Text = "other", "Contact"
		case dm.GetPollCreate() != nil:
			base.Kind, base.Text = "other", "Poll: "+dm.GetPollCreate().GetQuestion()
		case dm.GetPayment() != nil:
			base.Kind, base.Text = "other", "Payment"
		default:
			return nil
		}
		return []message{base}
	}
	out := make([]message, 0, len(atts))
	for i, a := range atts {
		m := base
		if i > 0 {
			m.ID, m.Text, m.ReplyTo = base.ID+"|"+strconv.Itoa(i), "", ""
		}
		if a.GetContentType() == longText {
			m.Kind = "text"
		} else {
			m.Kind, m.Mime, m.FileName, m.Size = kindOf(a.GetContentType(), a.GetFlags()&uint32(signalpb.AttachmentPointer_VOICE_MESSAGE) != 0, dm.GetSticker() != nil), a.GetContentType(), a.GetFileName(), uint64(a.GetSize())
		}
		out = append(out, m)
	}
	return out
}

// attachments lists a message's files, the long text first as Signal sends it, and a sticker as its image.
func attachments(dm *signalpb.DataMessage) []*signalpb.AttachmentPointer {
	if s := dm.GetSticker(); s != nil && s.GetData() != nil {
		return []*signalpb.AttachmentPointer{s.GetData()}
	}
	return dm.GetAttachments()
}

func attachmentOf(dm *signalpb.DataMessage, i int) *signalpb.AttachmentPointer {
	if atts := attachments(dm); i < len(atts) {
		return atts[i]
	}
	return nil
}

func kindOf(ct string, voice, sticker bool) string {
	switch {
	case sticker:
		return "sticker"
	case voice:
		return "voice"
	case strings.HasPrefix(ct, "image/"):
		return "image"
	case strings.HasPrefix(ct, "video/"):
		return "video"
	case strings.HasPrefix(ct, "audio/"):
		return "audio"
	}
	return "file"
}

func messageID(sender uuid.UUID, ts uint64) string {
	return sender.String() + "|" + strconv.FormatUint(ts, 10)
}

// parseMessageID reads a message id's sender and timestamp; the attachment index, if any, doesn't matter.
func parseMessageID(id string) (uuid.UUID, uint64, error) {
	parts := strings.Split(id, "|")
	if len(parts) < 2 {
		return uuid.Nil, 0, fmt.Errorf("bad message id")
	}
	sender, err := uuid.Parse(parts[0])
	if err != nil {
		return uuid.Nil, 0, fmt.Errorf("bad message id")
	}
	ts, err := strconv.ParseUint(parts[1], 10, 64)
	if err != nil {
		return uuid.Nil, 0, fmt.Errorf("bad message id")
	}
	return sender, ts, nil
}

// chatOf names a chat: a group by its title, a person by the name the person gave them, or their profile's.
func (b *Bridge) chatOf(ctx context.Context, cli *signalmeow.Client, chatID string, group bool, lastMs int64) chat {
	c := chat{ID: chatID, Group: group, LastMs: lastMs, Unread: -1}
	if group {
		ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		if title, err := groupTitle(ctx, cli, types.GroupIdentifier(chatID)); err == nil {
			c.Name = title
		}
		return c
	}
	if sid, err := libsignalgo.ServiceIDFromString(chatID); err == nil && sid.Type == libsignalgo.ServiceIDTypeACI {
		if r, err := contact(ctx, cli, sid.UUID); err == nil && r != nil {
			c.Name, c.Phone = nameOf(r), digits(r.E164)
		}
	}
	return c
}

func nameOf(r *types.Recipient) string {
	for _, name := range []string{r.ContactName, r.Nickname, r.Profile.Name} {
		if name != "" {
			return name
		}
	}
	return ""
}

// digits drops the + of an E.164 number, as the contract wants.
func digits(e164 string) string { return strings.TrimPrefix(e164, "+") }

func fetch(a *signalpb.AttachmentPointer) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	return download(ctx, a)
}

func (b *Bridge) fetchMedia(m message, a *signalpb.AttachmentPointer) {
	out := media{ID: m.ID, Chat: m.Chat}
	data, err := fetch(a)
	if err == nil {
		// Ids hold a UUID, | and digits; the name is cleaned anyway since part of it comes off the network.
		name := strings.Map(func(r rune) rune {
			if r == '/' || r == '\\' || r == '.' || r == '|' {
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

// Go's mime table doesn't know AAC or most of Signal's types, so the usual ones are spelled out.
func extension(mime string) string {
	base, _, _ := strings.Cut(mime, ";")
	switch strings.TrimSpace(base) {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "video/mp4":
		return ".mp4"
	case "audio/aac":
		return ".aac"
	case "audio/mp4":
		return ".m4a"
	case "audio/ogg":
		return ".ogg"
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
