package signalbridge

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
	"google.golang.org/protobuf/proto"
)

// maxInline is the longest body Signal's apps show inline; a longer one goes as a long-text attachment too.
const maxInline = 2 * 1024

// upload and deliver reach Signal's servers; tests replace them. deliver takes a chat id, so a person's ACI for a
// receipt.
var (
	upload = func(ctx context.Context, cli *signalmeow.Client, data []byte) (*signalpb.AttachmentPointer, error) {
		return cli.UploadAttachment(ctx, data)
	}
	deliver = sendContent
)

// SendText sends a text to a chat and returns its message id. replyTo, a message id, may be empty.
func (b *Bridge) SendText(chatID, text, replyTo string) (string, error) {
	return b.send(chatID, replyTo, func(ctx context.Context, cli *signalmeow.Client, dm *signalpb.DataMessage) error {
		dm.Body = proto.String(text)
		return nil
	})
}

// SendMedia uploads the file at path and sends it with an optional caption. kind is "image", "video", "audio",
// "voice" or "file". Signal plays voice notes only as AAC, so a voice note in another format goes as audio. Signal
// carries no duration, so seconds isn't sent.
func (b *Bridge) SendMedia(chatID, path, kind, mime, caption, fileName string, seconds int64, replyTo string) (string, error) {
	if kind != "image" && kind != "video" && kind != "audio" && kind != "voice" && kind != "file" {
		return "", fmt.Errorf("can't send %q", kind)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return b.send(chatID, replyTo, func(ctx context.Context, cli *signalmeow.Client, dm *signalpb.DataMessage) error {
		att, err := upload(ctx, cli, data)
		if err != nil {
			return fmt.Errorf("upload: %w", err)
		}
		att.ContentType = proto.String(mime)
		if fileName != "" {
			att.FileName = proto.String(fileName)
		}
		if kind == "voice" && (mime == "audio/aac" || strings.HasPrefix(mime, "audio/mp4")) {
			att.Flags = proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE))
		}
		dm.Attachments = []*signalpb.AttachmentPointer{att}
		if caption != "" {
			dm.Body = proto.String(caption)
		}
		return nil
	})
}

var (
	lastSentMu sync.Mutex
	lastSent   uint64
)

// timestamp is a message's id on Signal, so two sends never share one.
func timestamp() uint64 {
	lastSentMu.Lock()
	defer lastSentMu.Unlock()
	lastSent = max(uint64(time.Now().UnixMilli()), lastSent+1)
	return lastSent
}

func (b *Bridge) send(chatID, replyTo string, fill func(context.Context, *signalmeow.Client, *signalpb.DataMessage) error) (string, error) {
	cli := b.client()
	if cli == nil {
		return "", ErrNotLinked
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	ts := timestamp()
	dm := &signalpb.DataMessage{Timestamp: proto.Uint64(ts)}
	if replyTo != "" {
		author, id, err := parseMessageID(replyTo)
		if err != nil {
			return "", err
		}
		dm.Quote = &signalpb.DataMessage_Quote{
			Id:              proto.Uint64(id),
			AuthorAciBinary: author[:],
			Type:            signalpb.DataMessage_Quote_NORMAL.Enum(),
		}
	}
	if err := fill(ctx, cli, dm); err != nil {
		return "", err
	}
	if body := dm.GetBody(); len(body) > maxInline {
		att, err := upload(ctx, cli, []byte(body))
		if err != nil {
			return "", fmt.Errorf("upload long text: %w", err)
		}
		att.ContentType = proto.String(longText)
		dm.Attachments = append([]*signalpb.AttachmentPointer{att}, dm.Attachments...)
		cut := maxInline
		for !utf8.RuneStart(body[cut]) {
			cut--
		}
		dm.Body = proto.String(body[:cut])
	}
	if err := deliver(ctx, cli, chatID, signalmeow.WrapDataMessage(dm)); err != nil {
		return "", err
	}
	return messageID(cli.Store.ACI, ts), nil
}

// sendContent sends to a group by its identifier, or to a person by their service id, as mautrix-signal does. A
// group send counts when it reached anyone.
func sendContent(ctx context.Context, cli *signalmeow.Client, chatID string, content *signalpb.Content) error {
	if !connected(cli) {
		return ErrNotConnected
	}
	if isGroup(chatID) {
		res, err := cli.SendGroupMessage(ctx, types.GroupIdentifier(chatID), content)
		if err != nil {
			return err
		}
		if len(res.SuccessfullySentTo) == 0 && len(res.FailedToSendTo) > 0 {
			return fmt.Errorf("not sent to anyone in the group: %w", res.FailureError())
		}
		return nil
	}
	to, err := libsignalgo.ServiceIDFromString(chatID)
	if err != nil {
		// The id itself stays out of the error.
		return errors.New("bad chat id")
	}
	res := cli.SendMessage(ctx, to, content)
	if !res.WasSuccessful {
		if res.Error != nil {
			return res.Error
		}
		return errors.New("not sent")
	}
	return nil
}

// A group identifier is 32 bytes in base64; a person's id is a UUID, with a PNI: prefix for a phone-number identity.
func isGroup(chatID string) bool { return len(chatID) == 44 }
