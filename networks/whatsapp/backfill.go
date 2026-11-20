package whatsapp

import (
	"context"
	"math/rand/v2"
	"time"

	"go.mau.fi/whatsmeow/types"
)

// History ahead of time (docs/OVERVIEW.md, WhatsApp). WhatsApp sends a linked device its history once, right after
// pairing. For more, the device asks the person's phone for the messages before the oldest one it has (whatsmeow's
// BuildHistorySyncRequest, a peer message to the phone); the phone answers when it's online with an ON_DEMAND history
// sync, which onHistory reads like the first one. So Messages opens a chat with its history already there and never
// waits on the phone while scrolling.
//
// Each connection asks for a few of the most recent chats that hold fewer than backfillCap messages, one request at a
// time, a few seconds apart. Every request wakes the person's phone with a push, so the numbers stay small. A request
// the phone doesn't answer is repeated after askAgain, at most maxUnanswered times; an answer with no messages means
// the chat's history is all here.
const (
	recentChats           = 10
	requestsPerConnection = 5
	requestSize           = 50 // whatsmeow's recommended batch
	backfillCap           = 100
	askAgain              = 6 * time.Hour
	maxUnanswered         = 3
)

// settle is how long the connection stays quiet of history syncs before the first request, so the sync that follows
// pairing or a reconnect arrives first.
const settle = 10 * time.Second

// wait sleeps for d, or until ctx ends; false means it ended.
func wait(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// gap is the pause between two requests, four to eight seconds at random.
func gap() time.Duration { return 4*time.Second + rand.N(4*time.Second) }

// startBackfill runs one pass for this connection, once; stopBackfill ends it with the connection.
func (b *Bridge) startBackfill() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.backfillCancel != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	b.backfillCancel = cancel
	b.connectedAt = time.Now()
	go b.backfill(ctx)
}

func (b *Bridge) stopBackfill() {
	b.mu.Lock()
	cancel := b.backfillCancel
	b.backfillCancel = nil
	b.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (b *Bridge) backfill(ctx context.Context) {
	for {
		b.mu.Lock()
		quiet := time.Since(b.connectedAt)
		if h := time.Since(b.historyAt); h < quiet {
			quiet = h
		}
		b.mu.Unlock()
		if quiet >= b.settle {
			break
		}
		if !b.wait(ctx, b.settle-quiet) {
			return
		}
	}
	for i, c := range b.wantedChats(time.Now()) {
		if i > 0 && !b.wait(ctx, gap()) {
			return
		}
		// Counted before it goes, since the answer can arrive before the request returns.
		_, _ = b.db.Exec(`UPDATE bridge_history SET unanswered = unanswered + 1, asked_at = ? WHERE chat = ?`,
			time.Now().UnixMilli(), c.Chat.String())
		if err := b.requestHistory(ctx, c, requestSize); err != nil {
			// Not connected any more, or WhatsApp refused; the next connection tries again.
			_, _ = b.db.Exec(`UPDATE bridge_history SET unanswered = MAX(unanswered - 1, 0) WHERE chat = ?`, c.Chat.String())
			return
		}
	}
}

// wantedChats are the oldest known messages of the most recent chats that want more history and may be asked now: not
// all here, under the cap, and with no request pending (one unanswered for askAgain counts as lost).
func (b *Bridge) wantedChats(now time.Time) []*types.MessageInfo {
	rows, err := b.db.Query(`SELECT chat, oldest_id, oldest_from_me, oldest_ms FROM (
			SELECT * FROM bridge_history ORDER BY activity DESC, chat LIMIT ?)
		WHERE done = 0 AND count < ? AND oldest_id != '' AND unanswered < ? AND (unanswered = 0 OR asked_at <= ?)
		ORDER BY activity DESC, chat LIMIT ?`,
		recentChats, backfillCap, maxUnanswered, now.Add(-askAgain).UnixMilli(), requestsPerConnection)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []*types.MessageInfo
	for rows.Next() {
		var chat, id string
		var fromMe bool
		var ms int64
		if rows.Scan(&chat, &id, &fromMe, &ms) != nil {
			continue
		}
		jid, err := types.ParseJID(chat)
		if err != nil {
			continue
		}
		out = append(out, &types.MessageInfo{
			MessageSource: types.MessageSource{Chat: jid, IsFromMe: fromMe},
			ID:            id,
			Timestamp:     time.UnixMilli(ms),
		})
	}
	return out
}

// noteMessage keeps a chat's activity, its oldest message (the anchor for the next request) and how many messages it
// has. The count includes the odd repeat, which only stops a chat's history a little early.
func (b *Bridge) noteMessage(m message, chat types.JID) {
	if !backfillable(chat) {
		return
	}
	_, _ = b.db.Exec(`INSERT INTO bridge_history (chat, activity, oldest_id, oldest_from_me, oldest_ms, count)
		VALUES (?, ?, ?, ?, ?, 1)
		ON CONFLICT (chat) DO UPDATE SET
			activity = MAX(activity, excluded.activity),
			count = count + 1,
			oldest_id = CASE WHEN oldest_id = '' OR excluded.oldest_ms < oldest_ms THEN excluded.oldest_id ELSE oldest_id END,
			oldest_from_me = CASE WHEN oldest_id = '' OR excluded.oldest_ms < oldest_ms THEN excluded.oldest_from_me ELSE oldest_from_me END,
			oldest_ms = CASE WHEN oldest_id = '' OR excluded.oldest_ms < oldest_ms THEN excluded.oldest_ms ELSE oldest_ms END`,
		m.Chat, m.Timestamp, m.ID, m.FromMe, m.Timestamp)
}

func (b *Bridge) noteActivity(chat types.JID, ms int64) {
	if !backfillable(chat) {
		return
	}
	_, _ = b.db.Exec(`INSERT INTO bridge_history (chat, activity) VALUES (?, ?)
		ON CONFLICT (chat) DO UPDATE SET activity = MAX(activity, excluded.activity)`, chat.String(), ms)
}

// noteAnswer records the phone's answer for a chat: one that brought nothing older means its history is all here.
func (b *Bridge) noteAnswer(chat types.JID, nothingOlder bool) {
	_, _ = b.db.Exec(`UPDATE bridge_history SET unanswered = 0, done = done OR ? WHERE chat = ?`, nothingOlder, chat.String())
}

func (b *Bridge) oldest(chat types.JID) int64 {
	var ms int64
	_ = b.db.QueryRow(`SELECT oldest_ms FROM bridge_history WHERE chat = ?`, chat.String()).Scan(&ms)
	return ms
}

// backfillable chats are people and groups; status updates and channels aren't asked for.
func backfillable(chat types.JID) bool {
	switch chat.Server {
	case types.DefaultUserServer, types.HiddenUserServer, types.GroupServer:
		return true
	}
	return false
}
