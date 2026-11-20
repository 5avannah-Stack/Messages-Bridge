// Package whatsapp is Messages Bridge's WhatsApp: a linked device on whatsmeow, bound into the Android app with
// gomobile (docs/OVERVIEW.md). Kotlin's WhatsAppConnector sees only what is exported here. gomobile binds strings,
// numbers, bools, []byte, exported structs and callback interfaces, but no slices of structs, so lists and events
// cross as JSON (events.go). The app keeps chats and history itself; this package keeps only whatsmeow's store.
//
// It never logs message text, numbers or names: whatsmeow's own logger stays off.
package whatsapp

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"

	"cz.teous/messages-bridge/networks/internal/sqlite"
)

// Listener receives every event from the bridge as its kind and JSON payload (events.go). It is called on
// whatsmeow's goroutines.
type Listener interface {
	OnEvent(kind string, payload string)
}

// Bridge is one WhatsApp linked device: its store, its connection and where its media goes.
type Bridge struct {
	db        *sql.DB
	container *sqlstore.Container
	cli       *whatsmeow.Client
	listener  Listener
	mediaDir  string

	// download and upload reach WhatsApp's media servers; tests replace them.
	download func(ctx context.Context, msg whatsmeow.DownloadableMessage) ([]byte, error)
	upload   func(ctx context.Context, data []byte, kind whatsmeow.MediaType) (whatsmeow.UploadResponse, error)

	mu       sync.Mutex
	qrSeen   chan struct{}      // closed on the login socket's first QR event, which PairPhone waits for
	qrCancel context.CancelFunc // stops a QR login
}

var (
	ErrAlreadyPaired = errors.New("already paired")
	ErrBadPhone      = errors.New("phone number must be international digits, without a leading 0")
	ErrNotPaired     = errors.New("not paired")
)

// The linked device shows in the person's WhatsApp under this name.
func init() {
	store.DeviceProps.Os = proto.String("Messages")
}

// Open opens (or creates) the store in dir and prepares a client; nothing connects until Connect or a login.
func Open(dir string, listener Listener) (*Bridge, error) {
	mediaDir := filepath.Join(dir, "media")
	if err := os.MkdirAll(mediaDir, 0o700); err != nil {
		return nil, fmt.Errorf("media folder: %w", err)
	}
	db, err := sqlite.Open(filepath.Join(dir, "wa.db"))
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	container := sqlstore.NewWithDB(db, "sqlite3", nil)
	if err := container.Upgrade(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("store upgrade: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("device: %w", err)
	}
	b := &Bridge{db: db, container: container, listener: listener, mediaDir: mediaDir}
	b.cli = whatsmeow.NewClient(device, nil)
	b.download = b.cli.Download
	b.upload = b.cli.Upload
	b.cli.AddEventHandler(b.handle)
	return b, nil
}

// IsPaired says whether this store holds a linked device.
func (b *Bridge) IsPaired() bool { return b.cli.Store.ID != nil }

// Account is the person's number, international digits, or "" before pairing.
func (b *Bridge) Account() string {
	if id := b.cli.Store.ID; id != nil {
		return id.User
	}
	return ""
}

// IsConnected says whether the socket is up.
func (b *Bridge) IsConnected() bool { return b.cli.IsConnected() }

// Connect opens the socket of a paired device. An unpaired one logs in with PairPhone or LoginQR.
func (b *Bridge) Connect() error {
	if !b.IsPaired() {
		return ErrNotPaired
	}
	return b.cli.Connect()
}

// Disconnect closes the socket and keeps the pairing.
func (b *Bridge) Disconnect() { b.cli.Disconnect() }

// Close disconnects and closes the store. The Bridge can't be used afterwards.
func (b *Bridge) Close() error {
	b.CancelLogin()
	b.cli.Disconnect()
	return b.container.Close()
}

// Logout unlinks this device from the person's WhatsApp and wipes its keys.
func (b *Bridge) Logout() error { return b.cli.Logout(context.Background()) }

// PairPhone links by phone-number code: it connects, waits for the login socket, and returns the 8-character code the
// person types into WhatsApp on their main phone (Linked devices, Link with phone number). WhatsApp also shows them a
// notification asking for it. The code works until the login socket closes, about 160 seconds; success comes as a
// "paired" event, failure as "pair_failed".
func (b *Bridge) PairPhone(phone string) (string, error) {
	if b.IsPaired() {
		return "", ErrAlreadyPaired
	}
	digits, err := internationalDigits(phone)
	if err != nil {
		return "", err
	}
	b.mu.Lock()
	b.qrSeen = make(chan struct{})
	seen := b.qrSeen
	b.mu.Unlock()
	if !b.cli.IsConnected() {
		if err := b.cli.Connect(); err != nil {
			return "", err
		}
	}
	select {
	case <-seen:
	case <-time.After(15 * time.Second):
		return "", errors.New("login socket didn't open")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return b.cli.PairPhone(ctx, digits, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
}

// LoginQR starts a QR login and returns the first code to show. Each renewed code comes as a "qr" event; success as
// "paired", and the end of the codes or an error as "qr_done".
func (b *Bridge) LoginQR() (string, error) {
	if b.IsPaired() {
		return "", ErrAlreadyPaired
	}
	b.CancelLogin()
	ctx, cancel := context.WithCancel(context.Background())
	codes, err := b.cli.GetQRChannel(ctx)
	if err != nil {
		cancel()
		return "", err
	}
	b.mu.Lock()
	b.qrCancel = cancel
	b.mu.Unlock()
	if err := b.cli.Connect(); err != nil {
		b.CancelLogin()
		return "", err
	}
	first, ok := <-codes
	if !ok || first.Event != whatsmeow.QRChannelEventCode {
		b.CancelLogin()
		return "", fmt.Errorf("no QR code: %s", first.Event)
	}
	go func() {
		for item := range codes {
			switch {
			case item.Event == whatsmeow.QRChannelEventCode:
				b.emit("qr", qr{Code: item.Code, TimeoutMs: item.Timeout.Milliseconds()})
			case item == whatsmeow.QRChannelSuccess:
				// "paired" comes from the PairSuccess event.
			default:
				b.emit("qr_done", qrDone{Result: item.Event})
			}
		}
	}()
	return first.Code, nil
}

// CancelLogin stops a QR login and closes an unpaired socket.
func (b *Bridge) CancelLogin() {
	b.mu.Lock()
	cancel := b.qrCancel
	b.qrCancel = nil
	b.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if !b.IsPaired() {
		b.cli.Disconnect()
	}
}

// SendText sends a text to a chat (a JID, or bare international digits for a person) and returns its message ID.
func (b *Bridge) SendText(chat string, text string) (string, error) {
	return b.send(chat, &waE2E.Message{Conversation: proto.String(text)})
}

// SendMedia uploads the file at path and sends it. kind is "image", "video", "audio", "voice" or "file"; caption may
// be empty. It returns the message ID.
func (b *Bridge) SendMedia(chat, path, kind, mime, caption, fileName string, seconds int64) (string, error) {
	mediaType, err := mediaTypeOf(kind)
	if err != nil {
		return "", err
	}
	if !b.IsPaired() {
		return "", ErrNotPaired
	}
	if !b.cli.IsConnected() {
		return "", whatsmeow.ErrNotConnected
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	up, err := b.upload(ctx, data, mediaType)
	if err != nil {
		return "", fmt.Errorf("upload: %w", err)
	}
	return b.send(chat, mediaMessage(kind, mime, caption, fileName, uint32(seconds), up))
}

func (b *Bridge) send(chat string, msg *waE2E.Message) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	if !b.IsPaired() {
		return "", ErrNotPaired
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := b.cli.SendMessage(ctx, jid, msg)
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}

// MarkRead sends a read receipt for one message and everything before it. sender matters in groups and may be empty
// in a one-to-one chat.
func (b *Bridge) MarkRead(chat string, sender string, messageID string, timestampMs int64) error {
	chatJID, err := parseChat(chat)
	if err != nil {
		return err
	}
	senderJID := types.EmptyJID
	if sender != "" {
		if senderJID, err = parseChat(sender); err != nil {
			return err
		}
	}
	if !b.IsPaired() {
		return ErrNotPaired
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return b.cli.MarkRead(ctx, []types.MessageID{messageID}, time.UnixMilli(timestampMs), chatJID, senderJID)
}

// internationalDigits keeps only the digits and rejects what WhatsApp would: too short, or a national number.
func internationalDigits(phone string) (string, error) {
	var digits strings.Builder
	for _, r := range phone {
		if r >= '0' && r <= '9' {
			digits.WriteRune(r)
		}
	}
	d := digits.String()
	if len(d) < 7 || strings.HasPrefix(d, "0") {
		return "", ErrBadPhone
	}
	return d, nil
}

// parseChat takes a full JID, or bare digits for a person's phone-number JID.
func parseChat(chat string) (types.JID, error) {
	if !strings.Contains(chat, "@") {
		digits, err := internationalDigits(chat)
		if err != nil {
			return types.EmptyJID, err
		}
		return types.NewJID(digits, types.DefaultUserServer), nil
	}
	jid, err := types.ParseJID(chat)
	if err != nil || jid.User == "" {
		// The id itself stays out of the error, since it may hold a number.
		return types.EmptyJID, errors.New("bad chat id")
	}
	return jid, nil
}
