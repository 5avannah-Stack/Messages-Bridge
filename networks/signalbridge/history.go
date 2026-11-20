package signalbridge

import (
	"context"
	"encoding/base64"
	"errors"
	"strconv"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/backuppb"
)

// History is what Signal offers a new linked device: when the person agrees on their phone while linking, the phone
// uploads a transfer archive of their chats, and this device downloads it once. Each chat's newest messages go to the
// app as history; the archive's copy is dropped afterwards. Media in it isn't downloaded, as with WhatsApp.
const (
	historyPerChat = 1000
	metaHistory    = "history"
)

func (b *Bridge) historyPending(ctx context.Context) bool {
	return b.cli.Store.EphemeralBackupKey != nil && b.meta(ctx, metaHistory) == ""
}

// syncHistory waits for the phone's archive (up to an hour, while connected) and reports it. A failure leaves it
// pending for the next connection; a refusal or a timeout ends it.
func (b *Bridge) syncHistory(ctx context.Context, cli *signalmeow.Client) {
	defer func() {
		b.mu.Lock()
		b.historyRun = false
		b.mu.Unlock()
	}()
	meta, err := cli.WaitForTransfer(ctx)
	switch {
	case errors.Is(err, signalmeow.ErrTransferTimedOut):
		b.setMeta(ctx, metaHistory, "timeout")
		return
	case err != nil:
		return
	case meta.Error != "":
		b.setMeta(ctx, metaHistory, "declined")
		return
	}
	if err := cli.FetchAndProcessTransfer(ctx, meta); err != nil {
		return
	}
	if err := b.reportHistory(ctx, cli); err != nil {
		return
	}
	_ = cli.Store.BackupStore.ClearBackup(ctx)
	b.setMeta(ctx, metaHistory, "done")
}

func (b *Bridge) reportHistory(ctx context.Context, cli *signalmeow.Client) error {
	chats, err := cli.Store.BackupStore.GetBackupChats(ctx)
	if err != nil {
		return err
	}
	recipients := map[uint64]*backuppb.Recipient{}
	recipient := func(id uint64) *backuppb.Recipient {
		if r, ok := recipients[id]; ok {
			return r
		}
		r, _ := cli.Store.BackupStore.GetBackupRecipient(ctx, id)
		recipients[id] = r
		return r
	}
	for _, bc := range chats {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		c, ok := historyChat(recipient(bc.RecipientId), cli.Store.ACI)
		if !ok || bc.TotalMessages == 0 {
			continue
		}
		items, err := cli.Store.BackupStore.GetBackupChatItems(ctx, bc.Id, time.Time{}, false, historyPerChat)
		if err != nil {
			return err
		}
		c.LastMs = int64(bc.LatestMessageID)
		c.Unread = 0
		var msgs []message
		for _, item := range items {
			author := recipientACI(recipient(item.AuthorId), cli.Store.ACI)
			if author == uuid.Nil {
				continue
			}
			m, ok := historyMessage(item, c.ID, author, func(id uint64) uuid.UUID { return recipientACI(recipient(id), cli.Store.ACI) })
			if !ok {
				continue
			}
			m.FromMe = author == cli.Store.ACI
			if in := item.GetIncoming(); in != nil && !in.Read {
				c.Unread++
			}
			msgs = append(msgs, m)
		}
		b.emit("chat", c)
		for _, m := range msgs {
			b.emit("message", m)
		}
	}
	return nil
}

// historyChat names an archived chat. Only people, the person's own notes and groups are chats here.
func historyChat(r *backuppb.Recipient, self uuid.UUID) (chat, bool) {
	switch dest := r.GetDestination().(type) {
	case *backuppb.Recipient_Self:
		return chat{ID: self.String()}, true
	case *backuppb.Recipient_Contact:
		ct := dest.Contact
		if len(ct.GetAci()) != 16 {
			return chat{}, false
		}
		c := chat{ID: uuid.UUID(ct.GetAci()).String()}
		if ct.E164 != nil {
			c.Phone = strconv.FormatUint(ct.GetE164(), 10)
		}
		for _, name := range []string{
			join(ct.GetSystemGivenName(), ct.GetSystemFamilyName()),
			join(ct.GetNickname().GetGiven(), ct.GetNickname().GetFamily()),
			join(ct.GetProfileGivenName(), ct.GetProfileFamilyName()),
		} {
			if name != "" {
				c.Name = name
				break
			}
		}
		return c, true
	case *backuppb.Recipient_Group:
		if len(dest.Group.GetMasterKey()) != libsignalgo.GroupMasterKeyLength {
			return chat{}, false
		}
		gid, err := libsignalgo.GroupMasterKey(dest.Group.GetMasterKey()).GroupIdentifier()
		if err != nil {
			return chat{}, false
		}
		return chat{ID: base64.StdEncoding.EncodeToString(gid[:]), Group: true, Name: dest.Group.GetSnapshot().GetTitle().GetTitle()}, true
	}
	return chat{}, false
}

func recipientACI(r *backuppb.Recipient, self uuid.UUID) uuid.UUID {
	switch dest := r.GetDestination().(type) {
	case *backuppb.Recipient_Self:
		return self
	case *backuppb.Recipient_Contact:
		if aci := dest.Contact.GetAci(); len(aci) == 16 {
			return uuid.UUID(aci)
		}
	}
	return uuid.Nil
}

// historyMessage reads one archived item: standard messages (text, the first attachment's kind) and stickers. Others,
// such as group updates and deleted messages, aren't history here.
func historyMessage(item *backuppb.ChatItem, chatID string, author uuid.UUID, aciOf func(uint64) uuid.UUID) (message, bool) {
	m := message{
		ID:        messageID(author, item.GetDateSent()),
		Chat:      chatID,
		Sender:    author.String(),
		Timestamp: int64(item.GetDateSent()),
		History:   true,
		Kind:      "text",
	}
	switch it := item.GetItem().(type) {
	case *backuppb.ChatItem_StandardMessage:
		sm := it.StandardMessage
		m.Text = sm.GetText().GetBody()
		if q := sm.GetQuote(); q != nil && q.TargetSentTimestamp != nil {
			if a := aciOf(q.GetAuthorId()); a != uuid.Nil {
				m.ReplyTo = messageID(a, q.GetTargetSentTimestamp())
			}
		}
		if atts := sm.GetAttachments(); len(atts) > 0 {
			p := atts[0].GetPointer()
			m.Mime, m.FileName, m.Size = p.GetContentType(), p.GetFileName(), uint64(p.GetLocatorInfo().GetSize())
			m.Kind = kindOf(p.GetContentType(), atts[0].GetFlag() == backuppb.MessageAttachment_VOICE_MESSAGE, false)
		} else if m.Text == "" {
			return m, false
		}
	case *backuppb.ChatItem_StickerMessage:
		p := it.StickerMessage.GetSticker().GetData()
		m.Kind, m.Mime, m.Size = "sticker", p.GetContentType(), uint64(p.GetLocatorInfo().GetSize())
	default:
		return m, false
	}
	return m, true
}

func join(given, family string) string {
	switch {
	case given == "":
		return family
	case family == "":
		return given
	}
	return given + " " + family
}
