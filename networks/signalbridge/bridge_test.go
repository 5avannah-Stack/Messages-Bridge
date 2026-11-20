package signalbridge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/backuppb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/signalpb"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/store"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
	"google.golang.org/protobuf/proto"
)

// Everything here is synthetic. Numbers use the +1 000 prefix, which no real line has, UUIDs are made up, and nothing
// connects: linking needs a real Signal account and is never tried.
var (
	self   = uuid.MustParse("00000000-0000-4000-8000-000000000001")
	friend = uuid.MustParse("00000000-0000-4000-8000-000000000002")
	other  = uuid.MustParse("00000000-0000-4000-8000-000000000003")
	group  = strings.Repeat("A", 43) + "="
)

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

func (r *recorder) all(kind string) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, e := range r.events {
		if e.kind == kind {
			out = append(out, e.payload)
		}
	}
	return out
}

// synthetic is a linked device as provisioning would store it, with fresh keys and made-up ids.
func synthetic(t *testing.T) *store.DeviceData {
	t.Helper()
	aciKeys, err := libsignalgo.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	pniKeys, err := libsignalgo.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	return &store.DeviceData{
		ACIIdentityKeyPair: aciKeys, PNIIdentityKeyPair: pniKeys, ACIRegistrationID: 1, PNIRegistrationID: 2,
		ACI: self, PNI: other, DeviceID: 2, Number: "+10000000001", Password: "synthetic",
	}
}

func openBridge(t *testing.T) (*Bridge, *recorder, string) {
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

// linkedBridge has a stored device, as after a link, and network lookups replaced.
func linkedBridge(t *testing.T) (*Bridge, *recorder) {
	t.Helper()
	b, r, dir := openBridge(t)
	if err := b.container.PutDevice(context.Background(), synthetic(t)); err != nil {
		t.Fatal(err)
	}
	b.Close()
	b2, err := Open(dir, r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b2.Close() })
	stub(t, &contact, func(context.Context, *signalmeow.Client, uuid.UUID) (*types.Recipient, error) {
		return &types.Recipient{ACI: friend, E164: "+10000000002", Profile: types.Profile{Name: "Synthetic Friend"}}, nil
	})
	stub(t, &groupTitle, func(context.Context, *signalmeow.Client, types.GroupIdentifier) (string, error) {
		return "Synthetic Group", nil
	})
	return b2, r
}

func stub[T any](t *testing.T, v *T, with T) {
	old := *v
	*v = with
	t.Cleanup(func() { *v = old })
}

func TestFreshStoreIsNotLinked(t *testing.T) {
	b, _, dir := openBridge(t)
	if b.IsLinked() || b.Account() != "" || b.Number() != "" {
		t.Fatal("a fresh store has no account")
	}
	if err := b.Connect(); !errors.Is(err, ErrNotLinked) {
		t.Fatalf("connect without a link: %v", err)
	}
	if _, err := b.SendText(friend.String(), "hi", ""); !errors.Is(err, ErrNotLinked) {
		t.Fatalf("send without a link: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "signal.db")); err != nil {
		t.Fatal("the store lives in the network's folder")
	}
}

func TestStoredDeviceComesBack(t *testing.T) {
	b, _ := linkedBridge(t)
	if !b.IsLinked() || b.Account() != self.String() || b.Number() != "10000000001" {
		t.Fatalf("linked %v as %q %q", b.IsLinked(), b.Account(), b.Number())
	}
}

func TestLinkRenewsItsCodeAndReportsTheDevice(t *testing.T) {
	b, r, _ := openBridge(t)
	stub(t, &qrRefresh, 50*time.Millisecond)
	calls := 0
	device := synthetic(t)
	stub(t, &provision, func(ctx context.Context, b *Bridge) chan signalmeow.ProvisioningResponse {
		calls++
		ch := make(chan signalmeow.ProvisioningResponse, 2)
		ch <- signalmeow.ProvisioningResponse{State: signalmeow.StateProvisioningURLReceived, ProvisioningURL: "sgnl://linkdevice?uuid=synthetic" + string(rune('0'+calls))}
		if calls == 2 {
			// The person scans the second code; provisioning stores the device.
			if err := b.container.PutDevice(ctx, device); err != nil {
				t.Error(err)
			}
			go func() {
				time.Sleep(10 * time.Millisecond)
				ch <- signalmeow.ProvisioningResponse{State: signalmeow.StateProvisioningDataReceived, ProvisioningData: device}
			}()
		}
		return ch
	})
	first, err := b.LoginQR()
	if err != nil || first != "sgnl://linkdevice?uuid=synthetic1" {
		t.Fatalf("first code %q, %v", first, err)
	}
	var renewed qr
	r.nextOf(t, "qr", &renewed)
	if renewed.Code != "sgnl://linkdevice?uuid=synthetic2" {
		t.Fatalf("renewed %q", renewed.Code)
	}
	var p paired
	r.nextOf(t, "paired", &p)
	if p.Account != self.String() || p.Number != "10000000001" || !b.IsLinked() {
		t.Fatalf("paired %+v", p)
	}
	if _, err := b.LoginQR(); !errors.Is(err, ErrAlreadyLinked) {
		t.Fatalf("second login: %v", err)
	}
}

func TestLinkGivesUpWithAReason(t *testing.T) {
	b, r, _ := openBridge(t)
	stub(t, &provision, func(ctx context.Context, b *Bridge) chan signalmeow.ProvisioningResponse {
		ch := make(chan signalmeow.ProvisioningResponse, 2)
		ch <- signalmeow.ProvisioningResponse{State: signalmeow.StateProvisioningURLReceived, ProvisioningURL: "sgnl://linkdevice?uuid=synthetic"}
		ch <- signalmeow.ProvisioningResponse{State: signalmeow.StateProvisioningError, Err: signalmeow.DeviceLinkError{StatusCode: 411}}
		return ch
	})
	if _, err := b.LoginQR(); err != nil {
		t.Fatal(err)
	}
	var done qrDone
	r.nextOf(t, "qr_done", &done)
	if !strings.Contains(done.Result, "most linked devices") {
		t.Fatalf("result %q", done.Result)
	}
}

func TestMessagesFromAPerson(t *testing.T) {
	b, r := linkedBridge(t)
	b.handle(&events.ChatEvent{
		Info: events.MessageInfo{Sender: friend, ChatID: friend.String()},
		Event: &signalpb.DataMessage{
			Timestamp: proto.Uint64(1800000000000),
			Body:      proto.String("synthetic text"),
			Quote:     &signalpb.DataMessage_Quote{Id: proto.Uint64(1799999999000), AuthorAciBinary: self[:]},
		},
	})
	var c chat
	r.nextOf(t, "chat", &c)
	if c.ID != friend.String() || c.Name != "Synthetic Friend" || c.Phone != "10000000002" || c.Group || c.Unread != -1 {
		t.Fatalf("chat %+v", c)
	}
	var m message
	r.nextOf(t, "message", &m)
	if m.ID != friend.String()+"|1800000000000" || m.Kind != "text" || m.Text != "synthetic text" || m.FromMe ||
		m.PushName != "Synthetic Friend" || m.SenderPhone != "10000000002" || m.ReplyTo != self.String()+"|1799999999000" {
		t.Fatalf("message %+v", m)
	}
}

func TestAttachmentsSplitAndDownload(t *testing.T) {
	b, r := linkedBridge(t)
	stub(t, &download, func(_ context.Context, a *signalpb.AttachmentPointer) ([]byte, error) {
		if a.GetContentType() == longText {
			return []byte("the whole long text"), nil
		}
		return []byte("synthetic bytes"), nil
	})
	b.handle(&events.ChatEvent{
		Info: events.MessageInfo{Sender: friend, ChatID: group},
		Event: &signalpb.DataMessage{
			Timestamp: proto.Uint64(1800000000001),
			Body:      proto.String("the whole"),
			GroupV2:   &signalpb.GroupContextV2{Revision: proto.Uint32(1)},
			Attachments: []*signalpb.AttachmentPointer{
				{ContentType: proto.String(longText)},
				{ContentType: proto.String("image/jpeg"), Size: proto.Uint32(15)},
				{ContentType: proto.String("audio/aac"), Flags: proto.Uint32(uint32(signalpb.AttachmentPointer_VOICE_MESSAGE))},
			},
		},
	})
	var c chat
	r.nextOf(t, "chat", &c)
	if !c.Group || c.Name != "Synthetic Group" {
		t.Fatalf("group %+v", c)
	}
	var text, image, voice message
	r.nextOf(t, "message", &text)
	r.nextOf(t, "message", &image)
	r.nextOf(t, "message", &voice)
	base := friend.String() + "|1800000000001"
	if text.ID != base || text.Kind != "text" || text.Text != "the whole long text" || text.Media {
		t.Fatalf("text %+v", text)
	}
	if image.ID != base+"|1" || image.Kind != "image" || !image.Media || image.Text != "" || image.Size != 15 {
		t.Fatalf("image %+v", image)
	}
	if voice.ID != base+"|2" || voice.Kind != "voice" {
		t.Fatalf("voice %+v", voice)
	}
	for range 2 {
		var got media
		r.nextOf(t, "media", &got)
		data, err := os.ReadFile(got.Path)
		if err != nil || string(data) != "synthetic bytes" || strings.Contains(filepath.Base(got.Path), "|") {
			t.Fatalf("media %+v: %v", got, err)
		}
	}
}

func TestSkipsWhatThisVersionDoesNotShow(t *testing.T) {
	sender := friend
	for name, dm := range map[string]*signalpb.DataMessage{
		"reaction": {Timestamp: proto.Uint64(1), Reaction: &signalpb.DataMessage_Reaction{Emoji: proto.String("👍")}},
		"delete":   {Timestamp: proto.Uint64(1), Delete: &signalpb.DataMessage_Delete{TargetSentTimestamp: proto.Uint64(1)}},
		"timer":    {Timestamp: proto.Uint64(1), Flags: proto.Uint32(uint32(signalpb.DataMessage_EXPIRATION_TIMER_UPDATE))},
		"empty":    {Timestamp: proto.Uint64(1)},
	} {
		if got := describe(sender, friend.String(), dm); len(got) != 0 {
			t.Errorf("%s: %+v", name, got)
		}
	}
	poll := describe(sender, friend.String(), &signalpb.DataMessage{Timestamp: proto.Uint64(1), PollCreate: &signalpb.DataMessage_PollCreate{Question: proto.String("Synthetic?")}})
	if len(poll) != 1 || poll[0].Kind != "other" || poll[0].Text != "Poll: Synthetic?" {
		t.Fatalf("poll %+v", poll)
	}
	sticker := describe(sender, friend.String(), &signalpb.DataMessage{Timestamp: proto.Uint64(1), Sticker: &signalpb.DataMessage_Sticker{Data: &signalpb.AttachmentPointer{ContentType: proto.String("image/webp")}}})
	if len(sticker) != 1 || sticker[0].Kind != "sticker" {
		t.Fatalf("sticker %+v", sticker)
	}
}

func TestReadsGoToEachSenderAndComeBackFromThePhone(t *testing.T) {
	b, r := linkedBridge(t)
	var mu sync.Mutex
	receipts := map[string][]uint64{}
	stub(t, &deliver, func(_ context.Context, _ *signalmeow.Client, to string, content *signalpb.Content) error {
		mu.Lock()
		defer mu.Unlock()
		receipts[to] = append(receipts[to], content.GetReceiptMessage().GetTimestamp()...)
		return nil
	})
	for i, s := range []uuid.UUID{friend, other, friend} {
		b.handle(&events.ChatEvent{
			Info:  events.MessageInfo{Sender: s, ChatID: group},
			Event: &signalpb.DataMessage{Timestamp: proto.Uint64(uint64(100 + i)), Body: proto.String("x"), GroupV2: &signalpb.GroupContextV2{}},
		})
	}
	b.handle(&events.ChatEvent{
		Info:  events.MessageInfo{Sender: friend, ChatID: friend.String()},
		Event: &signalpb.DataMessage{Timestamp: proto.Uint64(200), Body: proto.String("x")},
	})
	if err := b.MarkRead(group, messageID(other, 101)); err != nil {
		t.Fatal(err)
	}
	if len(receipts[friend.String()]) != 1 || receipts[friend.String()][0] != 100 || len(receipts[other.String()]) != 1 {
		t.Fatalf("receipts %v", receipts)
	}
	// The phone reads the rest: the group up to 102 and the person's chat.
	b.handle(&events.ReadSelf{Messages: []*signalpb.SyncMessage_Read{
		{SenderAciBinary: friend[:], Timestamp: proto.Uint64(102)},
		{SenderAciBinary: friend[:], Timestamp: proto.Uint64(200)},
	}})
	reads := map[string]string{}
	for range 2 {
		var rd read
		r.nextOf(t, "read", &rd)
		reads[rd.Chat] = rd.ID
	}
	if reads[group] != messageID(friend, 102) || reads[friend.String()] != messageID(friend, 200) {
		t.Fatalf("reads %v", reads)
	}
	if err := b.MarkRead(group, messageID(friend, 102)); err != nil || len(receipts[friend.String()]) != 1 {
		t.Fatalf("nothing is left to receipt: %v %v", err, receipts)
	}
}

func TestSendNamesTheMessageAndQuotes(t *testing.T) {
	b, _ := linkedBridge(t)
	var sent []*signalpb.DataMessage
	var to []string
	stub(t, &deliver, func(_ context.Context, _ *signalmeow.Client, chatID string, content *signalpb.Content) error {
		sent, to = append(sent, content.GetDataMessage()), append(to, chatID)
		return nil
	})
	stub(t, &upload, func(_ context.Context, _ *signalmeow.Client, data []byte) (*signalpb.AttachmentPointer, error) {
		return &signalpb.AttachmentPointer{Size: proto.Uint32(uint32(len(data)))}, nil
	})
	id, err := b.SendText(friend.String(), "synthetic", messageID(friend, 5))
	if err != nil {
		t.Fatal(err)
	}
	author, ts, err := parseMessageID(id)
	if err != nil || author != self || ts != sent[0].GetTimestamp() {
		t.Fatalf("id %q", id)
	}
	if q := sent[0].GetQuote(); q.GetId() != 5 || uuid.UUID(q.GetAuthorAciBinary()) != friend {
		t.Fatalf("quote %+v", q)
	}
	path := filepath.Join(t.TempDir(), "voice.m4a")
	if err := os.WriteFile(path, []byte("synthetic audio"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := b.SendMedia(group, path, "voice", "audio/mp4", "", "", 3, ""); err != nil {
		t.Fatal(err)
	}
	att := sent[1].GetAttachments()[0]
	if to[1] != group || att.GetContentType() != "audio/mp4" || att.GetFlags() != uint32(signalpb.AttachmentPointer_VOICE_MESSAGE) {
		t.Fatalf("voice note %+v to %s", att, to[1])
	}
	if sent[1].GetTimestamp() <= sent[0].GetTimestamp() {
		t.Fatal("timestamps name messages, so they never repeat")
	}
	long := strings.Repeat("é", maxInline)
	if _, err := b.SendText(friend.String(), long, ""); err != nil {
		t.Fatal(err)
	}
	if dm := sent[2]; len(dm.GetBody()) > maxInline || dm.GetAttachments()[0].GetContentType() != longText {
		t.Fatalf("long text %d bytes, %v", len(dm.GetBody()), dm.GetAttachments())
	}
	if _, err := b.SendMedia(friend.String(), path, "sticker", "image/webp", "", "", 0, ""); err == nil {
		t.Fatal("stickers aren't sent")
	}
}

func TestSendWithoutConnectionFails(t *testing.T) {
	b, _ := linkedBridge(t)
	if _, err := b.SendText(friend.String(), "synthetic", ""); !errors.Is(err, ErrNotConnected) {
		t.Fatalf("send offline: %v", err)
	}
}

func TestHistoryArchive(t *testing.T) {
	c, ok := historyChat(&backuppb.Recipient{Destination: &backuppb.Recipient_Contact{Contact: &backuppb.Contact{
		Aci: friend[:], E164: proto.Uint64(10000000002), ProfileGivenName: proto.String("Synthetic"), ProfileFamilyName: proto.String("Friend"),
	}}}, self)
	if !ok || c.ID != friend.String() || c.Name != "Synthetic Friend" || c.Phone != "10000000002" {
		t.Fatalf("chat %+v", c)
	}
	if _, ok := historyChat(&backuppb.Recipient{Destination: &backuppb.Recipient_ReleaseNotes{}}, self); ok {
		t.Fatal("release notes aren't a chat")
	}
	ids := map[uint64]uuid.UUID{1: self, 2: friend}
	item := &backuppb.ChatItem{
		AuthorId: 2, DateSent: 1700000000000,
		Item: &backuppb.ChatItem_StandardMessage{StandardMessage: &backuppb.StandardMessage{
			Text:        &backuppb.Text{Body: "from before"},
			Quote:       &backuppb.Quote{TargetSentTimestamp: proto.Uint64(1699999999999), AuthorId: 1},
			Attachments: []*backuppb.MessageAttachment{{Pointer: &backuppb.FilePointer{ContentType: proto.String("video/mp4")}}},
		}},
	}
	m, ok := historyMessage(item, friend.String(), friend, func(id uint64) uuid.UUID { return ids[id] })
	if !ok || !m.History || m.Media || m.Kind != "video" || m.Text != "from before" || m.ReplyTo != messageID(self, 1699999999999) {
		t.Fatalf("message %+v", m)
	}
	if _, ok := historyMessage(&backuppb.ChatItem{Item: &backuppb.ChatItem_UpdateMessage{}}, friend.String(), friend, nil); ok {
		t.Fatal("updates aren't history")
	}
}

func TestLoggedOutByThePhone(t *testing.T) {
	b, r := linkedBridge(t)
	b.handle(&events.LoggedOut{Error: errors.New("synthetic")})
	var s state
	r.nextOf(t, "state", &s)
	if s.State != "logged_out" {
		t.Fatalf("state %+v", s)
	}
}
