package whatsapp

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

// Everything here is synthetic. Numbers use the +1 000 prefix, which no real line has, and nothing connects:
// pairing needs a real WhatsApp account and is never tried.
var (
	self   = types.NewJID("10000000001", types.DefaultUserServer)
	friend = types.NewJID("10000000002", types.DefaultUserServer)
	group  = types.NewJID("120363000000000001", types.GroupServer)
	lid    = types.NewJID("100000000000003", types.HiddenUserServer)
	lidPN  = types.NewJID("10000000003", types.DefaultUserServer)
)

type event struct{ kind, payload string }

type recorder struct {
	mu     sync.Mutex
	events []event
	got    chan event
}

func newRecorder() *recorder { return &recorder{got: make(chan event, 64)} }

func (r *recorder) OnEvent(kind string, payload string) {
	r.mu.Lock()
	r.events = append(r.events, event{kind, payload})
	r.mu.Unlock()
	r.got <- event{kind, payload}
}

func (r *recorder) next(t *testing.T) event {
	t.Helper()
	select {
	case e := <-r.got:
		return e
	case <-time.After(5 * time.Second):
		t.Fatal("no event")
		return event{}
	}
}

// nextOf skips events of other kinds.
func (r *recorder) nextOf(t *testing.T, kind string, into any) {
	t.Helper()
	for {
		e := r.next(t)
		if e.kind == kind {
			if err := json.Unmarshal([]byte(e.payload), into); err != nil {
				t.Fatal(err)
			}
			return
		}
	}
}

func open(t *testing.T) (*Bridge, *recorder, string) {
	t.Helper()
	dir := t.TempDir()
	r := newRecorder()
	b, err := Open(dir, r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	return b, r, dir
}

// pairOffline saves the device as if pairing had finished, the way whatsmeow's pair handler does, so the stores that
// need a device JID (contacts, LIDs) work without a server.
func pairOffline(t *testing.T, b *Bridge) {
	t.Helper()
	id := self
	id.Device = 7
	b.cli.Store.ID = &id
	b.cli.Store.Account = &waAdv.ADVSignedDeviceIdentity{Details: []byte{}, AccountSignature: make([]byte, 64), AccountSignatureKey: make([]byte, 32), DeviceSignature: make([]byte, 64)}
	if err := b.cli.Store.Save(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestOpenCreatesAnUnpairedStore(t *testing.T) {
	b, _, dir := open(t)
	if b.IsPaired() || b.IsConnected() || b.Account() != "" {
		t.Fatal("a new store is neither paired nor connected")
	}
	if _, err := os.Stat(filepath.Join(dir, "wa.db")); err != nil {
		t.Fatal(err)
	}
	if contacts, _ := b.Contacts(); contacts != "[]" {
		t.Fatalf("contacts = %s", contacts)
	}
	if err := b.Connect(); !errors.Is(err, ErrNotPaired) {
		t.Fatalf("Connect without a device = %v", err)
	}
}

func TestPairingSurvivesReopening(t *testing.T) {
	dir := t.TempDir()
	b, err := Open(dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	pairOffline(t, b)
	b.Close()

	b, err = Open(dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if !b.IsPaired() || b.Account() != self.User {
		t.Fatal("the reopened store lost its device")
	}
	if _, err := b.PairPhone("+1 000 000 0001"); !errors.Is(err, ErrAlreadyPaired) {
		t.Fatalf("PairPhone on a paired store = %v", err)
	}
	if _, err := b.LoginQR(); !errors.Is(err, ErrAlreadyPaired) {
		t.Fatalf("LoginQR on a paired store = %v", err)
	}
}

func TestPairPhoneRejectsNationalNumbersBeforeConnecting(t *testing.T) {
	b, _, _ := open(t)
	for _, phone := range []string{"", "12345", "000 000 000", "0 1000 000 0001"} {
		if _, err := b.PairPhone(phone); !errors.Is(err, ErrBadPhone) {
			t.Errorf("PairPhone(%q) = %v", phone, err)
		}
	}
	if b.IsConnected() {
		t.Fatal("a bad number must not open a socket")
	}
}

func TestSendingNeedsAPairedConnectedDevice(t *testing.T) {
	b, _, dir := open(t)
	if _, err := b.SendText(friend.User, "hi"); !errors.Is(err, ErrNotPaired) {
		t.Fatalf("sent without a device: %v", err)
	}
	pairOffline(t, b)
	if _, err := b.SendText(friend.String(), "hi"); !errors.Is(err, whatsmeow.ErrNotConnected) {
		t.Fatalf("SendText while disconnected = %v", err)
	}
	file := filepath.Join(dir, "photo.jpg")
	os.WriteFile(file, []byte("synthetic"), 0o600)
	if _, err := b.SendMedia(friend.String(), file, "image", "image/jpeg", "", "", 0); !errors.Is(err, whatsmeow.ErrNotConnected) {
		t.Fatalf("SendMedia while disconnected = %v", err)
	}
	if _, err := b.SendMedia(friend.String(), file, "sticker", "image/webp", "", "", 0); err == nil {
		t.Fatal("sent a kind WhatsApp media can't carry")
	}
	if err := b.MarkRead(friend.String(), "", "3EB0000000000000000001", time.Now().UnixMilli()); err == nil {
		t.Fatal("marked read while disconnected")
	}
	if _, err := b.SendText("not a jid@", "hi"); err == nil {
		t.Fatal("accepted a broken JID")
	}
}

func TestMediaMessagesCarryTheUpload(t *testing.T) {
	up := whatsmeow.UploadResponse{URL: "https://example.invalid/m", DirectPath: "/m", MediaKey: []byte{1}, FileEncSHA256: []byte{2}, FileSHA256: []byte{3}, FileLength: 9}
	img := mediaMessage("image", "image/jpeg", "caption", "", 0, up).GetImageMessage()
	if img.GetCaption() != "caption" || img.GetDirectPath() != "/m" || img.GetFileLength() != 9 || img.GetMediaKey()[0] != 1 {
		t.Fatalf("image = %v", img)
	}
	voice := mediaMessage("voice", "audio/ogg; codecs=opus", "", "", 4, up).GetAudioMessage()
	if !voice.GetPTT() || voice.GetSeconds() != 4 {
		t.Fatalf("voice = %v", voice)
	}
	doc := mediaMessage("file", "application/pdf", "", "synthetic.pdf", 0, up).GetDocumentMessage()
	if doc.GetFileName() != "synthetic.pdf" || doc.Caption != nil {
		t.Fatalf("document = %v", doc)
	}
}

func incoming(id string, chat, sender types.JID, msg *waE2E.Message) *events.Message {
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{Chat: chat, Sender: sender, IsGroup: chat.Server == types.GroupServer},
			ID:            id,
			PushName:      "Synthetic Friend",
			Timestamp:     time.UnixMilli(1_800_000_000_000),
		},
		Message: msg,
	}
}

func TestTextMessagesBecomeEvents(t *testing.T) {
	b, r, _ := open(t)
	b.handle(incoming("A1", friend, friend, &waE2E.Message{Conversation: proto.String("plain")}))
	b.handle(incoming("A2", group, friend, &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text: proto.String("a reply"), ContextInfo: &waE2E.ContextInfo{StanzaID: proto.String("A1")}}}))
	b.handle(incoming("A3", friend, friend, &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{Text: proto.String("👍")}}))

	// A person's first live message names their chat with their push name.
	var named chat
	r.nextOf(t, "chat", &named)
	if named.JID != friend.String() || named.Name != "Synthetic Friend" || named.Phone != friend.User || named.Unread != -1 {
		t.Fatalf("chat = %+v", named)
	}
	var first message
	r.nextOf(t, "message", &first)
	if first.Kind != "text" || first.Text != "plain" || first.Chat != friend.String() || first.FromMe || first.Timestamp != 1_800_000_000_000 || first.History || first.SenderPhone != friend.User {
		t.Fatalf("message = %+v", first)
	}
	var second message
	r.nextOf(t, "message", &second)
	if second.Text != "a reply" || second.Chat != group.String() || second.Sender != friend.String() || second.ReplyTo != "A1" {
		t.Fatalf("group message = %+v", second)
	}
	r.mu.Lock()
	n := len(r.events)
	r.mu.Unlock()
	if n != 3 {
		t.Fatalf("a reaction is out of scope but made %d events", n-3)
	}
}

func TestLiveMediaIsDownloadedAfterItsMessage(t *testing.T) {
	b, r, dir := open(t)
	b.download = func(_ context.Context, msg whatsmeow.DownloadableMessage) ([]byte, error) {
		if _, ok := msg.(*waE2E.AudioMessage); ok {
			return []byte("OggS synthetic"), nil
		}
		return nil, errors.New("synthetic media server down")
	}
	b.handle(incoming("V1", group, friend, &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
		Mimetype: proto.String("audio/ogg; codecs=opus"), Seconds: proto.Uint32(4), PTT: proto.Bool(true)}}))
	var voice message
	r.nextOf(t, "message", &voice)
	if voice.Kind != "voice" || voice.Seconds != 4 || !voice.Media {
		t.Fatalf("voice = %+v", voice)
	}
	var got media
	r.nextOf(t, "media", &got)
	if got.ID != "V1" || got.Path != filepath.Join(dir, "media", "V1.ogg") {
		t.Fatalf("media = %+v", got)
	}
	if data, _ := os.ReadFile(got.Path); string(data) != "OggS synthetic" {
		t.Fatal("voice note not written")
	}

	b.handle(incoming("I1", group, friend, &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		Mimetype: proto.String("image/jpeg"), Caption: proto.String("caption")}}))
	var img message
	r.nextOf(t, "message", &img)
	if img.Kind != "image" || img.Text != "caption" {
		t.Fatalf("image = %+v", img)
	}
	var failed media
	r.nextOf(t, "media", &failed)
	if failed.ID != "I1" || failed.Path != "" || failed.Error == "" {
		t.Fatalf("a failed download must say so: %+v", failed)
	}
}

func TestHistorySyncBringsChatsAndMessages(t *testing.T) {
	b, r, _ := open(t)
	pairOffline(t, b)
	web := &waWeb.WebMessageInfo{
		Key:              &waCommon.MessageKey{RemoteJID: proto.String(friend.String()), FromMe: proto.Bool(false), ID: proto.String("H1")},
		Message:          &waE2E.Message{Conversation: proto.String("from before")},
		MessageTimestamp: proto.Uint64(1_700_000_000),
	}
	photo := &waWeb.WebMessageInfo{
		Key:              &waCommon.MessageKey{RemoteJID: proto.String(friend.String()), FromMe: proto.Bool(true), ID: proto.String("H2")},
		Message:          &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Mimetype: proto.String("image/jpeg")}},
		MessageTimestamp: proto.Uint64(1_700_000_001),
	}
	b.handle(&events.HistorySync{Data: &waHistorySync.HistorySync{Conversations: []*waHistorySync.Conversation{
		{ID: proto.String(friend.String()), ConversationTimestamp: proto.Uint64(1_700_000_001), UnreadCount: proto.Uint32(3),
			Messages: []*waHistorySync.HistorySyncMsg{{Message: web}, {Message: photo}}},
		{ID: proto.String(group.String()), Name: proto.String("Synthetic group"), ConversationTimestamp: proto.Uint64(1_700_000_100)},
		{ID: proto.String("")},
	}}})
	var c chat
	r.nextOf(t, "chat", &c)
	if c.JID != friend.String() || c.Unread != 3 || c.LastMs != 1_700_000_001_000 || c.Phone != friend.User || c.Group {
		t.Fatalf("chat = %+v", c)
	}
	var m message
	r.nextOf(t, "message", &m)
	if m.ID != "H1" || m.Text != "from before" || !m.History || m.Timestamp != 1_700_000_000_000 {
		t.Fatalf("history message = %+v", m)
	}
	var p message
	r.nextOf(t, "message", &p)
	if p.ID != "H2" || !p.FromMe || p.Kind != "image" || p.Media {
		t.Fatalf("history media isn't downloaded: %+v", p)
	}
	var g chat
	r.nextOf(t, "chat", &g)
	if g.Name != "Synthetic group" || !g.Group {
		t.Fatalf("group = %+v", g)
	}
}

func TestReadOnAnotherDeviceIsReported(t *testing.T) {
	b, r, _ := open(t)
	b.handle(&events.Receipt{
		MessageSource: types.MessageSource{Chat: friend, Sender: self, IsFromMe: true},
		MessageIDs:    []types.MessageID{"R1", "R2"},
		Type:          types.ReceiptTypeRead,
	})
	b.handle(&events.Receipt{MessageSource: types.MessageSource{Chat: friend, Sender: friend}, MessageIDs: []types.MessageID{"R3"}, Type: types.ReceiptTypeRead})
	var got read
	r.nextOf(t, "read", &got)
	if got.Chat != friend.String() || got.ID != "R2" {
		t.Fatalf("read = %+v", got)
	}
	select {
	case e := <-r.got:
		t.Fatalf("their read receipt isn't mine: %+v", e)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestPairingOutcomesAndConnectionState(t *testing.T) {
	b, r, _ := open(t)
	b.handle(&events.PairSuccess{ID: self})
	var p paired
	r.nextOf(t, "paired", &p)
	if p.Account != self.User {
		t.Fatalf("paired = %+v", p)
	}
	b.handle(&events.PairError{Error: errors.New("synthetic")})
	var f pairFailed
	r.nextOf(t, "pair_failed", &f)
	b.handle(&events.LoggedOut{})
	var s state
	r.nextOf(t, "state", &s)
	if s.State != "logged_out" {
		t.Fatalf("state = %+v", s)
	}
}

func TestContactsCarryTheirNumberAlsoBehindALID(t *testing.T) {
	b, _, _ := open(t)
	pairOffline(t, b)
	ctx := context.Background()
	s := b.cli.Store
	if err := s.Contacts.PutContactName(ctx, friend, "Synthetic", "Synthetic Friend"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Contacts.PutPushName(ctx, lid, "Pushed Name"); err != nil {
		t.Fatal(err)
	}
	if err := s.LIDs.PutLIDMapping(ctx, lid, lidPN); err != nil {
		t.Fatal(err)
	}
	var contacts []contact
	raw, err := b.Contacts()
	if err != nil {
		t.Fatal(err)
	}
	json.Unmarshal([]byte(raw), &contacts)
	byJID := map[string]contact{}
	for _, c := range contacts {
		byJID[c.JID] = c
	}
	if c := byJID[friend.String()]; c.Name != "Synthetic Friend" || c.Phone != friend.User {
		t.Fatalf("friend = %+v", c)
	}
	if c := byJID[lid.String()]; c.Name != "Pushed Name" || c.Phone != lidPN.User {
		t.Fatalf("LID contact = %+v", c)
	}
	if b.nameOf(friend) != "Synthetic Friend" {
		t.Fatal("nameOf missed the contact")
	}
}
