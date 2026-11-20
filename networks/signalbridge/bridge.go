// Package signalbridge is Messages Bridge's Signal: a linked device on signalmeow, mautrix-signal's Signal client, without
// its Matrix layer (docs/DECISIONS.md, Signal). It is bound into the Android app with gomobile beside WhatsApp, so the
// same rules hold: Kotlin's SignalConnector sees only what is exported here, and lists and events cross as JSON
// (events.go). The app keeps chats and history itself; this package keeps signalmeow's store and the few rows it needs
// to send read receipts (unread.go).
//
// It isn't called signal: gomobile writes a C header named after the package, and signal.h would hide the system's.
//
// It never logs message text, numbers or names: signalmeow's loggers get a disabled logger, and libsignal's own logger
// is never set up.
package signalbridge

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/store"
	"go.mau.fi/util/dbutil"

	"cz.teous/messages-bridge/networks/internal/sqlite"
)

// Listener receives every event from the bridge as its kind and JSON payload (events.go). It is called on
// signalmeow's goroutines.
type Listener interface {
	OnEvent(kind string, payload string)
}

// Bridge is one Signal linked device: its store, its connection and where its media goes.
type Bridge struct {
	db        *sql.DB
	container *store.Container
	listener  Listener
	mediaDir  string

	mu          sync.Mutex
	cli         *signalmeow.Client // nil until linked
	loginCancel context.CancelFunc
	connCancel  context.CancelFunc
	historyRun  bool
}

var (
	ErrAlreadyLinked = errors.New("already linked")
	ErrNotLinked     = errors.New("not linked")
	ErrNotConnected  = errors.New("not connected")
)

// DeviceName is how this device shows in the person's Signal, under Linked devices.
const DeviceName = "Messages"

// Open opens (or creates) the store in dir and prepares a client; nothing connects until Connect or a login.
func Open(dir string, listener Listener) (*Bridge, error) {
	mediaDir := filepath.Join(dir, "media")
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		return nil, fmt.Errorf("media folder: %w", err)
	}
	db, err := sqlite.Open(filepath.Join(dir, "signal.db"))
	if err != nil {
		return nil, err
	}
	b, err := open(db, listener, mediaDir)
	if err != nil {
		db.Close()
		return nil, err
	}
	return b, nil
}

func open(db *sql.DB, listener Listener, mediaDir string) (*Bridge, error) {
	wrapped, err := dbutil.NewWithDB(db, "sqlite3")
	if err != nil {
		return nil, fmt.Errorf("store: %w", err)
	}
	ctx := context.Background()
	container := store.NewStore(wrapped, dbutil.NoopLogger)
	if err := container.Upgrade(ctx); err != nil {
		return nil, fmt.Errorf("store upgrade: %w", err)
	}
	if err := createTables(ctx, db); err != nil {
		return nil, err
	}
	b := &Bridge{db: db, container: container, listener: listener, mediaDir: mediaDir}
	devices, err := container.GetAllDevices(ctx)
	if err != nil {
		return nil, fmt.Errorf("device: %w", err)
	}
	if len(devices) > 0 {
		b.cli = b.newClient(devices[0])
	}
	return b, nil
}

func (b *Bridge) newClient(device *store.Device) *signalmeow.Client {
	return signalmeow.NewClient(device, zerolog.Nop(), b.handle)
}

func (b *Bridge) client() *signalmeow.Client {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.cli
}

// IsLinked says whether this store holds a linked device that Signal hasn't logged out.
func (b *Bridge) IsLinked() bool {
	cli := b.client()
	return cli != nil && cli.IsLoggedIn()
}

// Account is the person's Signal account id (their ACI, a UUID), or "" before linking.
func (b *Bridge) Account() string {
	if cli := b.client(); cli != nil {
		return cli.Store.ACI.String()
	}
	return ""
}

// Number is the person's own number, international digits without +, or "".
func (b *Bridge) Number() string {
	if cli := b.client(); cli != nil {
		return digits(cli.Store.Number)
	}
	return ""
}

// IsConnected says whether both of Signal's sockets are up.
func (b *Bridge) IsConnected() bool { return connected(b.client()) }

// connected is signalmeow's IsConnected, which needs both sockets to have been opened once.
func connected(cli *signalmeow.Client) bool {
	return cli != nil && cli.AuthedWS != nil && cli.UnauthedWS != nil && cli.IsConnected()
}

// Connect opens the sockets of a linked device and returns once they are opening; "state" events follow. After a
// link it also fetches the history the person's phone offered (history.go).
func (b *Bridge) Connect() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cli == nil || !b.cli.IsLoggedIn() {
		return ErrNotLinked
	}
	if b.connCancel != nil {
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	statuses, err := b.cli.StartReceiveLoops(ctx)
	if err != nil {
		cancel()
		return err
	}
	b.connCancel = cancel
	go b.watch(b.cli, statuses)
	if !b.historyRun && b.historyPending(ctx) {
		b.historyRun = true
		go b.syncHistory(ctx, b.cli)
	}
	return nil
}

// Disconnect closes the sockets and keeps the link.
func (b *Bridge) Disconnect() {
	b.mu.Lock()
	cli, cancel := b.cli, b.connCancel
	b.connCancel = nil
	b.historyRun = false
	b.mu.Unlock()
	if cancel == nil {
		return
	}
	cancel()
	_ = cli.StopReceiveLoops()
	b.emit("state", state{State: "disconnected"})
}

// Close disconnects and closes the store. The Bridge can't be used afterwards.
func (b *Bridge) Close() error {
	b.CancelLogin()
	b.Disconnect()
	return b.db.Close()
}

// Logout unlinks this device from the person's Signal and wipes its keys. Unlinking goes over the socket, so it
// connects first when it has to.
func (b *Bridge) Logout() error {
	cli := b.client()
	if cli == nil {
		return ErrNotLinked
	}
	if !connected(cli) {
		if err := b.Connect(); err != nil {
			return err
		}
		deadline := time.Now().Add(15 * time.Second)
		for !connected(cli) && time.Now().Before(deadline) {
			time.Sleep(200 * time.Millisecond)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	unlinkErr := cli.Unlink(ctx)
	b.Disconnect()
	if err := b.container.DeleteDevice(ctx, &cli.Store.DeviceData); err != nil && unlinkErr == nil {
		unlinkErr = err
	}
	b.mu.Lock()
	b.cli = nil
	b.mu.Unlock()
	return unlinkErr
}

// watch turns signalmeow's connection statuses into "state" events until the loops stop.
func (b *Bridge) watch(cli *signalmeow.Client, statuses chan signalmeow.SignalConnectionStatus) {
	for s := range statuses {
		switch s.Event {
		case signalmeow.SignalConnectionEventConnected:
			b.emit("state", state{State: "connected"})
		case signalmeow.SignalConnectionEventDisconnected, signalmeow.SignalConnectionEventError:
			b.emit("state", state{State: "disconnected", Reason: errText(s.Err)})
		case signalmeow.SignalConnectionEventLoggedOut:
			// Unlinked from the person's phone, or Signal dropped the device: the keys are worthless now.
			_ = cli.ClearKeysAndDisconnect(context.Background())
			b.emit("state", state{State: "logged_out", Reason: errText(s.Err)})
		case signalmeow.SignalConnectionEventFatalError:
			b.emit("state", state{State: "error", Reason: errText(s.Err)})
		case signalmeow.SignalConnectionCleanShutdown:
			if !cli.IsLoggedIn() {
				b.emit("state", state{State: "logged_out"})
			}
		}
	}
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
