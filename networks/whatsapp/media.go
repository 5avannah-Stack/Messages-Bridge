package whatsapp

import (
	"fmt"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func mediaTypeOf(kind string) (whatsmeow.MediaType, error) {
	switch kind {
	case "image":
		return whatsmeow.MediaImage, nil
	case "video":
		return whatsmeow.MediaVideo, nil
	case "audio", "voice":
		return whatsmeow.MediaAudio, nil
	case "file":
		return whatsmeow.MediaDocument, nil
	}
	return "", fmt.Errorf("can't send %q", kind)
}

// mediaMessage wraps an uploaded file in the message WhatsApp expects for its kind.
func mediaMessage(kind, mime, caption, fileName string, seconds uint32, up whatsmeow.UploadResponse) *waE2E.Message {
	opt := func(s string) *string {
		if s == "" {
			return nil
		}
		return proto.String(s)
	}
	switch kind {
	case "image":
		return &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
			Caption: opt(caption), Mimetype: proto.String(mime), URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: proto.Uint64(up.FileLength),
		}}
	case "video":
		return &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
			Caption: opt(caption), Mimetype: proto.String(mime), Seconds: proto.Uint32(seconds), URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: proto.Uint64(up.FileLength),
		}}
	case "audio", "voice":
		return &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
			Mimetype: proto.String(mime), Seconds: proto.Uint32(seconds), PTT: proto.Bool(kind == "voice"), URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: proto.Uint64(up.FileLength),
		}}
	}
	return &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
		Caption: opt(caption), Mimetype: proto.String(mime), FileName: opt(fileName), Title: opt(fileName), URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
		MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: proto.Uint64(up.FileLength),
	}}
}
