package instagram

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/util/jsontime"
)

// Everything here is synthetic: made-up ids, usernames and cookie values, and a fake client. Nothing reaches
// Instagram; logging in needs a real account and is never tried.
const (
	self      = int64(100000000000001)
	friend    = int64(100000000000002)
	threadID  = "340282366841710300949128100000000001"
	threadKey = int64(100000000000002)
	longID    = "340282366841710300949128100000000099"
)

var syntheticCookies = `{"sessionid":"synthetic-session","csrftoken":"synthetic-csrf","ds_user_id":"1","rur":"synthetic"}`

type event struct{ kind, payload string }

type recorder struct {
	mu     sync.Mutex
	events []event
	got    chan event
}

func newRecorder() *recorder { return &recorder{got: make(chan event, 256)} }

func (r *recorder) OnEvent(kind string, payload string) {
	r.mu.Lock()
	r.events = append(r.events, event{kind, payload})
	r.mu.Unlock()
	r.got <- event{kind, payload}
}

func (r *recorder) nextOf(t *testing.T, kind string, into any) {
	t.Helper()
	for {
		select {
		case e := <-r.got:
			if e.kind == kind {
				if err := json.Unmarshal([]byte(e.payload), into); err != nil {
					t.Fatal(err)
				}
				return
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("no %s event", kind)
		}
	}
}

// fake stands in for instameow's client and records what the bridge asked of it.
type fake struct {
	mu         sync.Mutex
	handler    func(context.Context, slidetypes.ClientEvent) error
	loadErr    error
	seq        int64
	connected  chan struct{}
	threads    int
	texts      []*slidetypes.SendTextRequest
	medias     []*slidetypes.SendMediaRequest
	reads      []*slidetypes.MarkReadRequest
	validation []*slidetypes.MarkReadRequest
	mailboxes  int
	pages      []string // the cursor of each older page asked for
	history    *history // when set, threads carry messages and pages come from it
}

func newFake() *fake { return &fake{connected: make(chan struct{}, 4)} }

func (f *fake) LoadIndex(context.Context) (*types.PolarisViewer, *slidetypes.Mailbox, error) {
	if f.loadErr != nil {
		return nil, nil, f.loadErr
	}
	f.seq = 5
	mb := &slidetypes.Mailbox{UQSeqID: 5}
	mb.ThreadsByFolder.Edges = []slidetypes.Node[slidetypes.WrappedThreadInfo]{{Node: slidetypes.WrappedThreadInfo{AsIGDirectThread: f.thread()}}}
	v := &types.PolarisViewer{ID: "1"}
	v.Data.Username = "synthetic.self"
	return v, mb, nil
}
func (f *fake) GetOwnFBID() int64               { return self }
func (f *fake) LoadState(json.RawMessage) error { return nil }
func (f *fake) DumpState() (json.RawMessage, error) {
	return json.RawMessage(`{"synthetic":true}`), nil
}
func (f *fake) SetSeqID(seq int64, _ time.Time) { f.seq = seq }
func (f *fake) HasSeqID() bool                  { return f.seq != 0 }
func (f *fake) Connect(context.Context)         { f.connected <- struct{}{} }
func (f *fake) Disconnect()                     {}
func (f *fake) SetEventHandler(h func(context.Context, slidetypes.ClientEvent) error) {
	f.handler = h
}
func (f *fake) GetMailbox(context.Context) (*slidetypes.MailboxResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.mailboxes++
	mb := &slidetypes.Mailbox{}
	mb.ThreadsByFolder.Edges = []slidetypes.Node[slidetypes.WrappedThreadInfo]{{Node: slidetypes.WrappedThreadInfo{AsIGDirectThread: f.thread()}}}
	return &slidetypes.MailboxResponse{Mailbox: mb}, nil
}
func (f *fake) GetThread(context.Context, *slidetypes.GetThreadInfoRequest) (*slidetypes.ThreadInfoResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.threads++
	t := f.thread()
	if f.history != nil {
		t.SlideMessages = f.history.page("")
	}
	return &slidetypes.ThreadInfoResponse{ThreadInfo: slidetypes.WrappedThreadInfo{AsIGDirectThread: t}}, nil
}
func (f *fake) PaginateMessages(_ context.Context, req *slidetypes.PaginateMessagesRequest) (*slidetypes.PaginateMessagesResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.pages = append(f.pages, *req.AfterCursor)
	resp := &slidetypes.PaginateMessagesResponse{}
	resp.ThreadInfo.AsIGDirectThread = &slidetypes.MessagesOnlyThread{ID: req.ThreadID, Messages: f.history.page(*req.AfterCursor)}
	return resp, nil
}
func (f *fake) thread() *slidetypes.ThreadInfo {
	t := thread()
	if f.history != nil {
		t.LastActivityTimestampMS = jsontime.UnixMilliString{Time: time.UnixMilli(f.history.newest)}
	}
	return t
}
func (f *fake) SendMessage(_ context.Context, req *slidetypes.SendTextRequest) (*slidetypes.SendTextResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.texts = append(f.texts, req)
	return &slidetypes.SendTextResponse{Message: slidetypes.SentMessage{ID: "mid.sent-text"}}, nil
}
func (f *fake) SendMedia(_ context.Context, req *slidetypes.SendMediaRequest) (*slidetypes.SendMediaResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.medias = append(f.medias, req)
	return &slidetypes.SendMediaResponse{Message: slidetypes.SentMessage{ID: "mid.sent-media"}}, nil
}
func (f *fake) MarkRead(_ context.Context, req *slidetypes.MarkReadRequest) (*slidetypes.MarkReadResponse, error) {
	f.reads = append(f.reads, req)
	return &slidetypes.MarkReadResponse{}, nil
}
func (f *fake) MarkReadValidation(_ context.Context, req *slidetypes.MarkReadRequest) (*slidetypes.MarkReadValidationResponse, error) {
	f.validation = append(f.validation, req)
	return &slidetypes.MarkReadValidationResponse{}, nil
}

func thread() *slidetypes.ThreadInfo {
	return &slidetypes.ThreadInfo{
		ID: threadID, ThreadID: longID, ThreadKey: threadKey,
		Users: []*slidetypes.User{{FullName: "Synthetic Friend", Username: "synthetic.friend", InteropMessagingUserFBID: friend}},
	}
}

// history is a synthetic thread of total messages, one a second up to newest, served newest first in pages of
// pageSize; a page's cursor is the index of its first message.
type history struct {
	total  int
	newest int64
}

func (h *history) page(cursor string) *slidetypes.SlideMessages {
	from := 0
	if cursor != "" {
		from, _ = strconv.Atoi(cursor)
	}
	page := &slidetypes.SlideMessages{}
	for i := from; i < h.total && i < from+pageSize; i++ {
		page.Edges = append(page.Edges, slidetypes.Node[*slidetypes.Message]{Node: &slidetypes.Message{
			ID: fmt.Sprintf("mid.history%03d", i), SenderFBID: friend,
			TimestampMS: jsontime.UnixMilliString{Time: time.UnixMilli(h.newest - int64(i)*1000)},
			Content:     slidetypes.MessageContentWrapper{Content: &slidetypes.MessageContentText{TextBody: "synthetic"}},
		}})
	}
	if next := from + pageSize; next < h.total {
		page.PageInfo = slidetypes.PageInfo{HasNextPage: true, EndCursor: strconv.Itoa(next)}
	}
	return page
}

func stub[T any](t *testing.T, v *T, with T) {
	old := *v
	*v = with
	t.Cleanup(func() { *v = old })
}

func open(t *testing.T) (*Bridge, *recorder, *fake, string) {
	t.Helper()
	f := newFake()
	stub(t, &newClient, func(*cookies.Cookies) client { return f })
	dir := t.TempDir()
	r := newRecorder()
	b, err := Open(dir, r)
	if err != nil {
		t.Fatal(err)
	}
	// History waits until a test lets it run (TestHistoryIsReadAheadOfTime).
	b.pace = func(ctx context.Context) bool { <-ctx.Done(); return false }
	t.Cleanup(func() { b.Close() })
	return b, r, f, dir
}

// loggedIn has a session and a connection, as after a login and connect().
func loggedIn(t *testing.T) (*Bridge, *recorder, *fake) {
	t.Helper()
	b, r, f, _ := open(t)
	if _, err := b.LoginCookies(syntheticCookies); err != nil {
		t.Fatal(err)
	}
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	<-f.connected
	return b, r, f
}

// parse reads a message as Instagram's events carry it.
func parse(t *testing.T, raw string) *slidetypes.Message {
	t.Helper()
	var m slidetypes.Message
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	return &m
}

func TestFreshStoreIsLoggedOut(t *testing.T) {
	b, _, _, dir := open(t)
	if b.IsLoggedIn() || b.Account() != "" {
		t.Fatal("a fresh store has no session")
	}
	if err := b.Connect(); !errors.Is(err, ErrNotLoggedIn) {
		t.Fatalf("connect: %v", err)
	}
	if _, err := b.SendText(threadID, "hi", ""); !errors.Is(err, ErrNotLoggedIn) {
		t.Fatalf("send: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "instagram.db")); err != nil {
		t.Fatal("the store lives in the network's folder")
	}
}

func TestLoginKeepsTheSessionAndListsChats(t *testing.T) {
	b, r, f, dir := open(t)
	if _, err := b.LoginCookies(`{"sessionid":"synthetic"}`); err == nil || !strings.Contains(err.Error(), "csrftoken") {
		t.Fatalf("missing cookies: %v", err)
	}
	f.loadErr = errors.New("synthetic checkpoint")
	if _, err := b.LoginCookies(syntheticCookies); err == nil || b.IsLoggedIn() {
		t.Fatal("a session Instagram refuses isn't kept")
	}
	f.loadErr = nil
	account, err := b.LoginCookies(syntheticCookies)
	if err != nil || account != "1" {
		t.Fatalf("login %q: %v", account, err)
	}
	var c chat
	r.nextOf(t, "chat", &c)
	if c.ID != threadID || c.Name != "Synthetic Friend" || c.Handle != "synthetic.friend" || c.Group {
		t.Fatalf("chat %+v", c)
	}
	b.Close()
	again, err := Open(dir, r)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	if !again.IsLoggedIn() || again.Account() != "1" || again.Username() != "synthetic.self" {
		t.Fatal("the session survives a restart")
	}
	if _, err := again.LoginCookies(syntheticCookies); !errors.Is(err, ErrAlreadyLoggedIn) {
		t.Fatalf("second login: %v", err)
	}
	if err := again.Logout(); err != nil || again.IsLoggedIn() || again.Account() != "" {
		t.Fatalf("logout: %v", err)
	}
}

func TestMessagesFromANewChat(t *testing.T) {
	b, r, f := loggedIn(t)
	// A chat the login's inbox page didn't list.
	if _, err := b.db.Exec(`DELETE FROM bridge_thread`); err != nil {
		t.Fatal(err)
	}
	stub(t, &download, func(context.Context, string, string) ([]byte, error) { return []byte("synthetic audio"), nil })
	f.handler(context.Background(), &slidetypes.Delta{ThreadIGID: "", Data: &slidetypes.NewMessageEvent{Message: parse(t, `{
		"id":"mid.synthetic1","thread_fbid":"`+threadID+`","sender_fbid":"100000000000002","timestamp_ms":"1800000000000",
		"replied_to_message_id":"mid.synthetic0","sender":{"name":"Synthetic","user_dict":{"full_name":"Synthetic Friend"}},
		"content":{"__typename":"SlideMessageAudiosContent","audio_attachments":[{"playable_duration_ms":2500,
		"attachment_cdn_url":"https://example.invalid/voice"}]}}`)}})
	var c chat
	r.nextOf(t, "chat", &c)
	if c.Name != "Synthetic Friend" {
		t.Fatalf("first the chat's details: %+v", c)
	}
	var m message
	r.nextOf(t, "message", &m)
	if m.ID != "mid.synthetic1" || m.Chat != threadID || m.Kind != "voice" || m.Seconds != 3 || !m.Media || m.FromMe ||
		m.PushName != "Synthetic Friend" || m.ReplyTo != "mid.synthetic0" || m.Timestamp != 1800000000000 {
		t.Fatalf("message %+v", m)
	}
	var got media
	r.nextOf(t, "media", &got)
	if data, err := os.ReadFile(got.Path); err != nil || string(data) != "synthetic audio" {
		t.Fatalf("media %+v: %v", got, err)
	}
	if f.threads != 1 {
		t.Fatalf("thread fetched %d times", f.threads)
	}
	// Read on the phone: up to the newest message the bridge has.
	f.handler(context.Background(), &slidetypes.Delta{ThreadIGID: threadID, Data: &slidetypes.MarkReadEvent{}})
	var rd read
	r.nextOf(t, "read", &rd)
	if rd.Chat != threadID || rd.ID != "mid.synthetic1" {
		t.Fatalf("read %+v", rd)
	}
}

func TestWhatEachContentBecomes(t *testing.T) {
	cases := map[string]struct{ content, kind, url string }{
		"text":    {`{"__typename":"SlideMessageText","text_body":"synthetic"}`, "text", ""},
		"photo":   {`{"__typename":"SlideMessageImageContent","attachments":[{"attachment_cdn_url":"https://example.invalid/p"}]}`, "image", "https://example.invalid/p"},
		"video":   {`{"__typename":"SlideMessageVideosContent","videos":[{"attachment_cdn_fallback_url":"https://example.invalid/v"}]}`, "video", "https://example.invalid/v"},
		"sticker": {`{"__typename":"SlideMessageAnimatedMediaContent","animated_media":[{"is_sticker":true,"attachment_webp_url":"https://example.invalid/s"}]}`, "sticker", "https://example.invalid/s"},
		"once":    {`{"__typename":"SlideMessageRavenImageContent","view_mode":0,"attachment":{}}`, "other", ""},
		"post":    {`{"__typename":"SlideMessageXMAContent","xma_text_body":"","xma":{"title_text":"Synthetic post","target_url":"https://example.invalid/post"}}`, "other", ""},
		"notice":  {`{"__typename":"SlideMessageAdminText","text_fragments":[]}`, "", ""},
	}
	for name, c := range cases {
		m, url := describe(parse(t, `{"id":"mid.x","sender_fbid":"100000000000001","timestamp_ms":"1","content":`+c.content+`}`), threadID, self)
		if m.Kind != c.kind || url != c.url || (c.kind != "" && !m.FromMe) {
			t.Errorf("%s: %+v %q", name, m, url)
		}
	}
	post, _ := describe(parse(t, `{"id":"mid.x","timestamp_ms":"1","content":`+cases["post"].content+`}`), threadID, self)
	if post.Text != "Synthetic post\nhttps://example.invalid/post" {
		t.Errorf("shared post %q", post.Text)
	}
}

func TestSendAndRead(t *testing.T) {
	b, _, f := loggedIn(t)
	id, err := b.SendText(threadID, "synthetic", "mid.synthetic0")
	if err != nil || id != "mid.sent-text" {
		t.Fatalf("send %q: %v", id, err)
	}
	if req := f.texts[0]; *req.IGThreadIGID != threadID || *req.ReplyToMessageID != "mid.synthetic0" || req.Text.Value != "synthetic" {
		t.Fatalf("request %+v", req)
	}
	var uploadedTo int64
	var voice bool
	stub(t, &upload, func(_ context.Context, _ client, key int64, _ []byte, _, _ string, v bool) (int64, error) {
		uploadedTo, voice = key, v
		return 77, nil
	})
	path := filepath.Join(t.TempDir(), "voice.m4a")
	if err := os.WriteFile(path, []byte("synthetic"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := b.SendMedia(threadID, path, "voice", "audio/mp4", "and a caption", "", 3, ""); err != nil {
		t.Fatal(err)
	}
	if uploadedTo != threadKey || !voice || f.medias[0].AttachmentFBID != "77" || f.medias[0].ThreadID != threadID {
		t.Fatalf("media %+v to %d", f.medias[0], uploadedTo)
	}
	if len(f.texts) != 2 || f.texts[1].Text.Value != "and a caption" {
		t.Fatal("the caption follows as a text")
	}
	if _, err := b.SendMedia(threadID, path, "file", "application/pdf", "", "", 0, ""); err == nil {
		t.Fatal("Instagram takes no files")
	}
	if err := b.MarkRead(threadID, "mid.synthetic1", 1800000000000); err != nil {
		t.Fatal(err)
	}
	if f.reads[0].Metadata.IGThreadIGID != longID || f.validation[0].Data.MessageTimestampMS.UnixMilli() != 1800000000000 {
		t.Fatalf("read %+v %+v", f.reads[0], f.validation[0])
	}
	b.Disconnect()
	if _, err := b.SendText(threadID, "synthetic", ""); !errors.Is(err, ErrNotConnected) {
		t.Fatalf("send while asleep: %v", err)
	}
}

func TestConnectionStates(t *testing.T) {
	b, r, f := loggedIn(t)
	f.handler(context.Background(), &slidetypes.Connected{})
	var s state
	r.nextOf(t, "state", &s)
	if s.State != "connected" {
		t.Fatalf("state %+v", s)
	}
	f.handler(context.Background(), &slidetypes.AuthError{Error: errors.New("synthetic")})
	r.nextOf(t, "state", &s)
	if s.State != "logged_out" {
		t.Fatalf("state %+v", s)
	}
	f.handler(context.Background(), &slidetypes.SeqIDUpdate{SeqID: 9})
	if b.meta(metaSeqID) != "9" {
		t.Fatal("the position is kept for the next connection")
	}
}

func TestHistoryIsReadAheadOfTime(t *testing.T) {
	b, r, f, _ := open(t)
	f.history = &history{total: 130, newest: 1_800_000_000_000}
	var paced int
	var pacedMu sync.Mutex
	b.pace = func(ctx context.Context) bool {
		pacedMu.Lock()
		paced++
		pacedMu.Unlock()
		return ctx.Err() == nil
	}
	if _, err := b.LoginCookies(syntheticCookies); err != nil {
		t.Fatal(err)
	}
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	// The newest page, then older pages up to the cap, all as history and without media.
	for i := 0; i < backfillCap; i++ {
		var m message
		r.nextOf(t, "message", &m)
		if m.ID != fmt.Sprintf("mid.history%03d", i) || !m.History || m.Media || m.Chat != threadID || m.Text != "synthetic" {
			t.Fatalf("message %d: %+v", i, m)
		}
	}
	waitFor(t, func() bool {
		f.mu.Lock()
		defer f.mu.Unlock()
		return len(f.pages) == 4
	})
	b.Disconnect()
	idle(t, b)
	if f.threads != 1 || f.mailboxes != 0 || strings.Join(f.pages, ",") != "20,40,60,80" {
		t.Fatalf("thread %d, inbox %d, pages %v: the login's inbox is fresh, and the cap stops the pages", f.threads, f.mailboxes, f.pages)
	}
	if paced != 5 {
		t.Fatalf("%d of 5 requests paced", paced)
	}
	if b.thread(threadID).lastMsg != "mid.history000" {
		t.Fatal("read receipts go up to the newest message history brought")
	}

	// A later wake with nothing new reads only the inbox.
	b.inboxAt = time.Time{}
	b.backfill(context.Background(), f)
	if f.mailboxes != 1 || f.threads != 1 || len(f.pages) != 4 {
		t.Fatalf("idle wake: inbox %d, thread %d, pages %d", f.mailboxes, f.threads, len(f.pages))
	}
	// A chat with news gets its newest page again, and its older pages stay where they were.
	f.history.newest += 5_000
	b.inboxAt = time.Time{}
	b.backfill(context.Background(), f)
	if f.mailboxes != 2 || f.threads != 2 || len(f.pages) != 4 {
		t.Fatalf("wake with news: inbox %d, thread %d, pages %d", f.mailboxes, f.threads, len(f.pages))
	}
	// A pass ends with the connection.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	f.history.newest += 5_000
	b.inboxAt = time.Time{}
	b.backfill(ctx, f)
	if f.mailboxes != 2 {
		t.Fatal("nothing is asked after a disconnect")
	}
}

func TestHistoryStopsAtTheFirstMessage(t *testing.T) {
	b, _, f := loggedIn(t)
	b.Disconnect()
	idle(t, b)
	f.history = &history{total: 30, newest: 1_800_000_000_000}
	b.pace = func(ctx context.Context) bool { return ctx.Err() == nil }
	b.inboxAt = time.Time{}
	b.backfill(context.Background(), f)
	b.backfill(context.Background(), f)
	if f.threads != 1 || strings.Join(f.pages, ",") != "20" {
		t.Fatalf("thread %d, pages %v", f.threads, f.pages)
	}
}

// idle waits for the connection's history pass to end.
func idle(t *testing.T, b *Bridge) {
	t.Helper()
	waitFor(t, func() bool {
		b.mu.Lock()
		defer b.mu.Unlock()
		return !b.backfilling
	})
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
