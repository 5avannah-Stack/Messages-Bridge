// Package instagram is Messages Bridge's Instagram: the person's own web session on instameow, the Instagram client
// inside mautrix-meta, used without its Matrix layer (docs/DECISIONS.md, Instagram). It is bound into the Android app
// with gomobile beside WhatsApp and Signal, with the same rules: Kotlin's InstagramConnector sees only what is exported
// here, and lists and events cross as JSON (events.go). Instagram's DMs aren't end-to-end encrypted, so there are no
// keys: the session is the cookies from Instagram's own login page, kept with the client's reconnection state in
// `instagram.db`.
//
// It never logs message text, usernames or names: instameow gets a disabled logger.
package instagram

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"go.mau.fi/util/exhttp"

	"cz.teous/messages-bridge/networks/internal/sqlite"
)

// Listener receives every event from the bridge as its kind and JSON payload (events.go). It is called on
// instameow's goroutines.
type Listener interface {
	OnEvent(kind string, payload string)
}

// Bridge is one Instagram account: its session, its connection and where its media goes.
type Bridge struct {
	db       *sql.DB
	listener Listener
	mediaDir string

	mu         sync.Mutex
	cli        client // nil until logged in
	cookies    *cookies.Cookies
	self       int64 // the person's messaging id (FBID), which their own messages carry as sender
	connCancel context.CancelFunc
}

var (
	ErrAlreadyLoggedIn = errors.New("already logged in")
	ErrNotLoggedIn     = errors.New("not logged in")
	ErrNotConnected    = errors.New("not connected")
)

// client is the part of instameow the bridge uses; tests replace it.
type client interface {
	LoadIndex(ctx context.Context) (*types.PolarisViewer, *slidetypes.Mailbox, error)
	GetOwnFBID() int64
	LoadState(state json.RawMessage) error
	DumpState() (json.RawMessage, error)
	SetSeqID(seqID int64, ts time.Time)
	HasSeqID() bool
	SetEventHandler(handler func(context.Context, slidetypes.ClientEvent) error)
	Connect(ctx context.Context)
	Disconnect()
	GetThread(ctx context.Context, req *slidetypes.GetThreadInfoRequest) (*slidetypes.ThreadInfoResponse, error)
	SendMessage(ctx context.Context, req *slidetypes.SendTextRequest) (*slidetypes.SendTextResponse, error)
	SendMedia(ctx context.Context, req *slidetypes.SendMediaRequest) (*slidetypes.SendMediaResponse, error)
	MarkRead(ctx context.Context, req *slidetypes.MarkReadRequest) (*slidetypes.MarkReadResponse, error)
	MarkReadValidation(ctx context.Context, req *slidetypes.MarkReadRequest) (*slidetypes.MarkReadValidationResponse, error)
}

// newClient builds instameow's client over a session; tests replace it.
var newClient = func(c *cookies.Cookies) client {
	return instameow.NewClient(instameow.ClientParams{Cookies: c, Log: zerolog.Nop(), Settings: exhttp.SensibleClientSettings})
}

// Open opens (or creates) the store in dir; nothing connects until Connect or a login.
func Open(dir string, listener Listener) (*Bridge, error) {
	mediaDir := filepath.Join(dir, "media")
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		return nil, fmt.Errorf("media folder: %w", err)
	}
	db, err := sqlite.Open(filepath.Join(dir, "instagram.db"))
	if err != nil {
		return nil, err
	}
	b := &Bridge{db: db, listener: listener, mediaDir: mediaDir}
	if err := b.createTables(); err != nil {
		db.Close()
		return nil, err
	}
	if raw := b.meta(metaCookies); raw != "" {
		c := &cookies.Cookies{Platform: types.Instagram}
		if err := json.Unmarshal([]byte(raw), c); err == nil && c.IsLoggedIn() {
			c.Platform = types.Instagram
			b.cookies = c
			b.self, _ = strconv.ParseInt(b.meta(metaSelf), 10, 64)
		}
	}
	return b, nil
}

// IsLoggedIn says whether the store holds a session.
func (b *Bridge) IsLoggedIn() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.cookies != nil
}

// Account is the person's Instagram user id, or "" before a login.
func (b *Bridge) Account() string {
	if !b.IsLoggedIn() {
		return ""
	}
	return b.meta(metaAccount)
}

// Username is the person's Instagram handle, or "".
func (b *Bridge) Username() string { return b.meta(metaUsername) }

// LoginCookies logs in with the cookies Instagram's login page left, a JSON object of cookie names to values. It loads
// the inbox once to check them; the chats it lists arrive as "chat" events. It returns the person's account id.
func (b *Bridge) LoginCookies(values string) (string, error) {
	if b.IsLoggedIn() {
		return "", ErrAlreadyLoggedIn
	}
	var strs map[string]string
	if err := json.Unmarshal([]byte(values), &strs); err != nil {
		return "", errors.New("cookies must be a JSON object")
	}
	c := &cookies.Cookies{Platform: types.Instagram}
	named := make(map[cookies.MetaCookieName]string, len(strs))
	for k, v := range strs {
		named[cookies.MetaCookieName(k)] = v
	}
	c.UpdateValues(named)
	if missing := c.GetMissingCookieNames(); len(missing) > 0 {
		return "", fmt.Errorf("missing cookies: %v", missing)
	}
	cli := newClient(c)
	cli.SetEventHandler(b.handle)
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	viewer, mailbox, err := cli.LoadIndex(ctx)
	if err != nil {
		return "", loginError(err)
	}
	self := cli.GetOwnFBID()
	if self == 0 {
		return "", errors.New("Instagram didn't name the account")
	}
	raw, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	b.setMeta(metaCookies, string(raw))
	b.setMeta(metaSelf, strconv.FormatInt(self, 10))
	b.setMeta(metaAccount, viewer.ID)
	b.setMeta(metaUsername, viewer.GetUsername())
	b.setMeta(metaSeqID, strconv.FormatInt(mailbox.UQSeqID, 10))
	b.saveState(cli)
	b.mu.Lock()
	b.cookies, b.cli, b.self = c, cli, self
	b.mu.Unlock()
	b.onMailbox(mailbox)
	return viewer.ID, nil
}

func loginError(err error) error {
	return fmt.Errorf("Instagram didn't accept the login: %w", err)
}

// Connect opens Instagram's socket and returns; "state" events follow. A session with a saved position picks up
// where it left off; one without loads the inbox first.
func (b *Bridge) Connect() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cookies == nil {
		return ErrNotLoggedIn
	}
	if b.connCancel != nil {
		return nil
	}
	if b.cli == nil {
		b.cli = newClient(b.cookies)
		b.cli.SetEventHandler(b.handle)
	}
	cli := b.cli
	ctx, cancel := context.WithCancel(context.Background())
	b.connCancel = cancel
	go b.run(ctx, cli)
	return nil
}

func (b *Bridge) run(ctx context.Context, cli client) {
	seq, _ := strconv.ParseInt(b.meta(metaSeqID), 10, 64)
	if state := b.meta(metaState); seq != 0 && state != "" && cli.LoadState(json.RawMessage(state)) == nil {
		cli.SetSeqID(seq, time.Now())
	}
	if !cli.HasSeqID() {
		_, mailbox, err := cli.LoadIndex(ctx)
		if err != nil {
			if ctx.Err() == nil {
				b.emit("state", state{State: stateOf(err), Reason: err.Error()})
			}
			return
		}
		b.setMeta(metaSeqID, strconv.FormatInt(mailbox.UQSeqID, 10))
		b.saveState(cli)
		b.onMailbox(mailbox)
	}
	if ctx.Err() == nil {
		cli.Connect(ctx)
	}
}

// Disconnect closes the socket and keeps the session.
func (b *Bridge) Disconnect() {
	b.mu.Lock()
	cli, cancel := b.cli, b.connCancel
	b.connCancel = nil
	b.mu.Unlock()
	if cancel == nil {
		return
	}
	cancel()
	cli.Disconnect()
	b.emit("state", state{State: "disconnected"})
}

// Close disconnects and closes the store. The Bridge can't be used afterwards.
func (b *Bridge) Close() error {
	b.Disconnect()
	return b.db.Close()
}

// Logout forgets the session here. Instagram keeps it alive until it expires or the person ends it in Instagram
// (Settings › Accounts Center › Password and security › Where you're logged in), as mautrix-meta does too.
func (b *Bridge) Logout() error {
	b.Disconnect()
	b.mu.Lock()
	b.cli, b.cookies, b.self = nil, nil, 0
	b.mu.Unlock()
	_, err := b.db.Exec(`DELETE FROM bridge_meta; DELETE FROM bridge_thread;`)
	return err
}

func (b *Bridge) saveState(cli client) {
	if state, err := cli.DumpState(); err == nil {
		b.setMeta(metaState, string(state))
	}
}

func (b *Bridge) client() (client, int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.cli, b.self
}

// The bridge's own rows: the session and the client's position (bridge_meta), and each thread's ids, which Instagram
// spreads over three numbers that different calls want (bridge_thread).
const (
	metaCookies  = "cookies"
	metaSelf     = "self"
	metaAccount  = "account"
	metaUsername = "username"
	metaSeqID    = "seq_id"
	metaState    = "state"
)

func (b *Bridge) createTables() error {
	_, err := b.db.Exec(`
		CREATE TABLE IF NOT EXISTS bridge_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
		CREATE TABLE IF NOT EXISTS bridge_thread (
			id       TEXT PRIMARY KEY,
			long_id  TEXT NOT NULL DEFAULT '',
			key      INTEGER NOT NULL DEFAULT 0,
			last_msg TEXT NOT NULL DEFAULT ''
		);`)
	if err != nil {
		return fmt.Errorf("bridge tables: %w", err)
	}
	return nil
}

func (b *Bridge) meta(key string) string {
	var v string
	_ = b.db.QueryRow(`SELECT value FROM bridge_meta WHERE key = ?`, key).Scan(&v)
	return v
}

func (b *Bridge) setMeta(key, value string) {
	_, _ = b.db.Exec(`INSERT OR REPLACE INTO bridge_meta (key, value) VALUES (?, ?)`, key, value)
}
