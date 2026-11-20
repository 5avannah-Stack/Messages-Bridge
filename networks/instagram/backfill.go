package instagram

import (
	"context"
	"math/rand/v2"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

// History ahead of time (docs/OVERVIEW.md, Instagram). Instagram gives a new web session no messages, only the
// inbox's stubs, so the bridge reads history the way Instagram's web client does, a page at a time, before the person
// opens a chat: Messages opens a chat with its history already there and never waits on Instagram while scrolling.
//
// Each connection (a login, a wake, the catch-up) runs one pass. The inbox's first page says which chats changed;
// each changed chat among the most recent gets its newest page, and then the most recent chats get older pages until
// each holds backfillCap messages, as Messages' server bridge keeps. Requests go one at a time, a few seconds apart at
// random, and stop when the bridge disconnects; the next connection carries on from the saved cursors.
const (
	recentChats = 20  // chats whose newest page is kept current
	deepChats   = 10  // of those, the chats that get older pages too
	backfillCap = 100 // messages a chat is read back to
	pageSize    = 20  // messages a page, as Instagram's web client asks
	inboxFresh  = 2 * time.Minute
)

// pace waits before each request to Instagram, two to five seconds at random; false means the connection ended.
func pace(ctx context.Context) bool {
	t := time.NewTimer(2*time.Second + rand.N(3*time.Second))
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// backfill runs one pass for this connection; a pass already running keeps going alone.
func (b *Bridge) backfill(ctx context.Context, cli client) {
	b.mu.Lock()
	if b.backfilling {
		b.mu.Unlock()
		return
	}
	b.backfilling = true
	inboxAt := b.inboxAt
	b.mu.Unlock()
	defer func() {
		b.mu.Lock()
		b.backfilling = false
		b.mu.Unlock()
	}()

	// A failed request ends the pass: a lost session shows on the socket, and anything else waits for the next wake.
	if time.Since(inboxAt) > inboxFresh {
		if !b.pace(ctx) {
			return
		}
		resp, err := cli.GetMailbox(ctx)
		if err != nil {
			return
		}
		b.onMailbox(resp.GetMailbox())
	}
	for _, id := range b.staleThreads() {
		if !b.pace(ctx) {
			return
		}
		resp, err := cli.GetThread(ctx, slidetypes.MakeGetThreadInfoRequest(id))
		if err != nil || resp.ThreadInfo.AsIGDirectThread == nil {
			return
		}
		t := resp.ThreadInfo.AsIGDirectThread
		b.onThread(t)
		b.onNewestPage(id, t)
	}
	for _, id := range b.shallowThreads() {
		for {
			cursor, ok := b.olderCursor(id)
			if !ok {
				break
			}
			if !b.pace(ctx) {
				return
			}
			resp, err := cli.PaginateMessages(ctx, &slidetypes.PaginateMessagesRequest{
				AfterCursor: &cursor, ThreadID: id, FirstN: pageSize, InitialMessagePageCount: pageSize,
			})
			if err != nil || resp.ThreadInfo.AsIGDirectThread == nil {
				return
			}
			b.onOlderPage(id, resp.ThreadInfo.AsIGDirectThread.Messages)
		}
	}
}

// staleThreads are the recent chats never read, or with activity newer than the newest message the bridge has.
func (b *Bridge) staleThreads() []string {
	return b.threadIDs(`SELECT id FROM (SELECT * FROM bridge_backfill ORDER BY activity DESC, id LIMIT ?)
		WHERE (count = 0 AND more = 1) OR newest < activity ORDER BY activity DESC, id`, recentChats)
}

// shallowThreads are the most recent chats with older pages left to read.
func (b *Bridge) shallowThreads() []string {
	return b.threadIDs(`SELECT id FROM (SELECT * FROM bridge_backfill ORDER BY activity DESC, id LIMIT ?)
		WHERE more = 1 AND cursor != '' AND count < ? ORDER BY activity DESC, id`, deepChats, backfillCap)
}

func (b *Bridge) threadIDs(query string, args ...any) []string {
	rows, err := b.db.Query(query, args...)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	return ids
}

func (b *Bridge) olderCursor(id string) (string, bool) {
	var cursor string
	err := b.db.QueryRow(`SELECT cursor FROM bridge_backfill WHERE id = ? AND more = 1 AND cursor != '' AND count < ?`,
		id, backfillCap).Scan(&cursor)
	return cursor, err == nil
}

// onNewestPage reports a chat's newest page. Messages the bridge already has are repeats, which the hub ignores. A
// chat read for the first time starts its older pages here; later reads leave the cursor where it is.
func (b *Bridge) onNewestPage(id string, t *slidetypes.ThreadInfo) {
	page := t.SlideMessages
	if page == nil {
		page = &slidetypes.SlideMessages{}
	}
	newest := b.reportHistory(id, page)
	// Seen up to the thread's activity, so activity that brings no message (a reaction, a read) isn't fetched again.
	b.noteNewest(id, max(newest, t.LastActivityTimestampMS.UnixMilli()))
	_, _ = b.db.Exec(`UPDATE bridge_backfill SET count = ?, cursor = ?, more = ? WHERE id = ? AND count = 0 AND more = 1`,
		len(page.Edges), page.PageInfo.EndCursor, more(page), id)
}

func (b *Bridge) onOlderPage(id string, page *slidetypes.SlideMessages) {
	if page == nil {
		page = &slidetypes.SlideMessages{}
	}
	b.reportHistory(id, page)
	_, _ = b.db.Exec(`UPDATE bridge_backfill SET count = count + ?, cursor = ?, more = ? WHERE id = ?`,
		len(page.Edges), page.PageInfo.EndCursor, more(page), id)
}

// more says whether a page leads to an older one. An empty page ends the chat even if Instagram says otherwise.
func more(page *slidetypes.SlideMessages) bool {
	return page.PageInfo.HasNextPage && page.PageInfo.EndCursor != "" && len(page.Edges) > 0
}

// reportHistory sends a page's messages as history, without their media, as WhatsApp's and Signal's history. It
// returns the newest message's time, and makes it the chat's last message (for read receipts) when it's newer than
// any the bridge has.
func (b *Bridge) reportHistory(id string, page *slidetypes.SlideMessages) int64 {
	_, self := b.client()
	known := b.newest(id)
	var newest int64
	var newestID string
	for _, e := range page.Edges {
		if e.Node == nil {
			continue
		}
		m, _ := describe(e.Node, id, self)
		if m.Kind == "" {
			continue
		}
		m.History = true
		b.emit("message", m)
		if m.Timestamp > newest {
			newest, newestID = m.Timestamp, m.ID
		}
	}
	if newestID != "" && newest > known {
		b.setLastMessage(id, newestID)
	}
	return newest
}

// noteActivity keeps a thread's last activity as Instagram last reported it.
func (b *Bridge) noteActivity(id string, ms int64) {
	_, _ = b.db.Exec(`INSERT INTO bridge_backfill (id, activity) VALUES (?, ?)
		ON CONFLICT (id) DO UPDATE SET activity = MAX(activity, excluded.activity)`, id, ms)
}

// noteNewest keeps the newest message the bridge has reported in a thread, which is also activity.
func (b *Bridge) noteNewest(id string, ms int64) {
	_, _ = b.db.Exec(`INSERT INTO bridge_backfill (id, activity, newest) VALUES (?, ?, ?)
		ON CONFLICT (id) DO UPDATE SET activity = MAX(activity, excluded.activity), newest = MAX(newest, excluded.newest)`,
		id, ms, ms)
}

func (b *Bridge) activity(id string) int64 {
	var ms int64
	_ = b.db.QueryRow(`SELECT activity FROM bridge_backfill WHERE id = ?`, id).Scan(&ms)
	return ms
}

func (b *Bridge) newest(id string) int64 {
	var ms int64
	_ = b.db.QueryRow(`SELECT newest FROM bridge_backfill WHERE id = ?`, id).Scan(&ms)
	return ms
}
