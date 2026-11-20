package instagram

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"os"
	"strconv"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/util/jsontime"
	"go.mau.fi/util/ptr"
)

// upload sends a file to Instagram's upload endpoint for a thread (by its numeric key) and returns its attachment id;
// tests replace it.
var upload = func(ctx context.Context, cli client, threadKey int64, data []byte, mime, fileName string, voice bool) (int64, error) {
	ig, ok := cli.(*instameow.Client)
	if !ok {
		return 0, errors.New("no uploader")
	}
	resp, err := ig.GetHTTP().SendMercuryUploadRequest(ctx, threadKey, &httpclient.MercuryUploadMedia{
		Filename: fileName, MimeType: mime, MediaData: data, IsVoiceClip: voice,
	})
	if err != nil {
		return 0, err
	}
	id := resp.Payload.RealMetadata.GetFbId()
	if id == 0 {
		return 0, errors.New("Instagram returned no attachment id")
	}
	return id, nil
}

// otid is the offline threading id Instagram's own clients send with a message, a timestamp-based random number.
func otid() string {
	return strconv.FormatInt(time.Now().UnixMilli()<<22|rand.Int64N(1<<22), 10)
}

func (b *Bridge) connected() (client, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	switch {
	case b.cookies == nil:
		return nil, ErrNotLoggedIn
	case b.cli == nil || b.connCancel == nil:
		return nil, ErrNotConnected
	}
	return b.cli, nil
}

// SendText sends a text to a chat and returns its message id. replyTo, a message id, may be empty.
func (b *Bridge) SendText(chatID, text, replyTo string) (string, error) {
	cli, err := b.connected()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	resp, err := cli.SendMessage(ctx, &slidetypes.SendTextRequest{
		IGThreadIGID:       &chatID,
		OfflineThreadingID: otid(),
		ReplyToMessageID:   nonEmpty(replyTo),
		Text:               slidetypes.SensitiveString{Value: text},
		SendAttribution:    ptr.Ptr("igd_web_chat_tab:in_thread"),
	})
	if err != nil {
		return "", err
	}
	return sent(resp.GetMessage())
}

// SendMedia uploads the file at path and sends it. kind is "image", "video", "audio", "voice" or "file"; Instagram
// takes photos, videos and voice notes, so audio goes as a voice note and files aren't sent. Instagram has no
// captions, so caption is sent as a text after the media. It returns the media message's id.
func (b *Bridge) SendMedia(chatID, path, kind, mime, caption, fileName string, seconds int64, replyTo string) (string, error) {
	if kind != "image" && kind != "video" && kind != "audio" && kind != "voice" {
		return "", fmt.Errorf("Instagram can't send %q", kind)
	}
	cli, err := b.connected()
	if err != nil {
		return "", err
	}
	t := b.thread(chatID)
	if t.key == 0 {
		return "", errors.New("chat not known yet")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	if fileName == "" {
		fileName = "media" + extension(mime)
	}
	attachment, err := upload(ctx, cli, t.key, data, mime, fileName, kind == "voice" || kind == "audio")
	if err != nil {
		return "", fmt.Errorf("upload: %w", err)
	}
	resp, err := cli.SendMedia(ctx, &slidetypes.SendMediaRequest{
		AttachmentFBID:     strconv.FormatInt(attachment, 10),
		ThreadID:           chatID,
		OfflineThreadingID: otid(),
		ReplyToMessageID:   nonEmpty(replyTo),
	})
	if err != nil {
		return "", err
	}
	id, err := sent(resp.GetMessage())
	if err == nil && caption != "" {
		_, err = b.SendText(chatID, caption, "")
	}
	return id, err
}

// MarkRead marks a chat read up to a message, here and for the others, as Instagram's web client does: the read, then
// its validation with the message's time.
func (b *Bridge) MarkRead(chatID, messageID string, timestampMs int64) error {
	cli, err := b.connected()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	t := b.thread(chatID)
	if t.longID == "" {
		resp, err := cli.GetThread(ctx, slidetypes.MakeGetThreadInfoRequest(chatID))
		if err != nil {
			return err
		} else if resp.ThreadInfo.AsIGDirectThread == nil {
			return errors.New("chat not found")
		}
		b.onThread(resp.ThreadInfo.AsIGDirectThread)
		t = b.thread(chatID)
	}
	meta := slidetypes.MarkReadMetadata{IGThreadIGID: t.longID}
	if _, err := cli.MarkRead(ctx, &slidetypes.MarkReadRequest{Metadata: meta, Data: slidetypes.MarkReadData{MessageID: messageID, ItemID: ptr.Ptr("")}}); err != nil {
		return err
	}
	_, err = cli.MarkReadValidation(ctx, &slidetypes.MarkReadRequest{Metadata: meta, Data: slidetypes.MarkReadData{
		MessageID: messageID, MessageTimestampMS: jsontime.UnixMilliString{Time: time.UnixMilli(timestampMs)},
	}})
	return err
}

func sent(m slidetypes.SentMessage) (string, error) {
	if m.ID == "" {
		return "", errors.New("Instagram returned no message id")
	}
	return m.ID, nil
}

func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
