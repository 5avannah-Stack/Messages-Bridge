package signalbridge

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
)

// Signal acknowledges reading message by message, to each sender, while the app marks a chat read up to one message.
// So the bridge keeps the others' unread messages, by chat, sender and timestamp, in its own table beside
// signalmeow's: reading up to a message sends one receipt per sender for everything before it, and a read on the
// person's phone, which names only senders and timestamps, finds its chat here. The table also says whether the
// history transfer after a link still has to run.
func createTables(ctx context.Context, db *sql.DB) error {
	_, err := db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS bridge_unread (
			chat   TEXT NOT NULL,
			sender TEXT NOT NULL,
			ts     INTEGER NOT NULL,
			id     TEXT NOT NULL,
			PRIMARY KEY (sender, ts)
		);
		CREATE INDEX IF NOT EXISTS bridge_unread_chat ON bridge_unread (chat, ts);
		CREATE TABLE IF NOT EXISTS bridge_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);`)
	if err != nil {
		return fmt.Errorf("bridge tables: %w", err)
	}
	return nil
}

func (b *Bridge) addUnread(ctx context.Context, chatID string, sender uuid.UUID, ts uint64, id string) {
	_, _ = b.db.ExecContext(ctx, `INSERT OR IGNORE INTO bridge_unread (chat, sender, ts, id) VALUES (?, ?, ?, ?)`,
		chatID, sender.String(), int64(ts), id)
}

// MarkRead sends read receipts for every unread message in a chat up to and including the one named, one receipt per
// sender. The person's other devices hear of it from Signal's sync copy of each receipt.
func (b *Bridge) MarkRead(chatID, messageID string) error {
	_, upTo, err := parseMessageID(messageID)
	if err != nil {
		return err
	}
	cli := b.client()
	if cli == nil {
		return ErrNotLinked
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	rows, err := b.db.QueryContext(ctx, `SELECT sender, ts FROM bridge_unread WHERE chat = ? AND ts <= ?`, chatID, int64(upTo))
	if err != nil {
		return err
	}
	bySender := map[uuid.UUID][]uint64{}
	for rows.Next() {
		var sender string
		var ts int64
		if err := rows.Scan(&sender, &ts); err != nil {
			rows.Close()
			return err
		}
		if id, err := uuid.Parse(sender); err == nil {
			bySender[id] = append(bySender[id], uint64(ts))
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for sender, stamps := range bySender {
		if sender == cli.Store.ACI {
			continue
		}
		if err := deliver(ctx, cli, sender.String(), signalmeow.ReadReceptMessageForTimestamps(stamps)); err != nil {
			return err
		}
	}
	_, err = b.db.ExecContext(ctx, `DELETE FROM bridge_unread WHERE chat = ? AND ts <= ?`, chatID, int64(upTo))
	return err
}

// readElsewhere reports reads from the person's other devices: per chat, the newest message read.
func (b *Bridge) readElsewhere(reads []*signalpb.SyncMessage_Read) {
	ctx := context.Background()
	newest := map[string]struct {
		ts int64
		id string
	}{}
	for _, r := range reads {
		sender, err := signalmeow.ParseStringOrBinaryUUID(r.GetSenderAci(), r.GetSenderAciBinary())
		if err != nil {
			continue
		}
		var chatID string
		row := b.db.QueryRowContext(ctx, `SELECT chat FROM bridge_unread WHERE sender = ? AND ts = ?`, sender.String(), int64(r.GetTimestamp()))
		if row.Scan(&chatID) != nil {
			continue
		}
		if n, ok := newest[chatID]; !ok || int64(r.GetTimestamp()) > n.ts {
			newest[chatID] = struct {
				ts int64
				id string
			}{int64(r.GetTimestamp()), messageID(sender, r.GetTimestamp())}
		}
	}
	for chatID, n := range newest {
		_, _ = b.db.ExecContext(ctx, `DELETE FROM bridge_unread WHERE chat = ? AND ts <= ?`, chatID, n.ts)
		b.emit("read", read{Chat: chatID, ID: n.id})
	}
}

func (b *Bridge) meta(ctx context.Context, key string) string {
	var v string
	_ = b.db.QueryRowContext(ctx, `SELECT value FROM bridge_meta WHERE key = ?`, key).Scan(&v)
	return v
}

func (b *Bridge) setMeta(ctx context.Context, key, value string) {
	_, _ = b.db.ExecContext(ctx, `INSERT OR REPLACE INTO bridge_meta (key, value) VALUES (?, ?)`, key, value)
}
