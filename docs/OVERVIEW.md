# Overview

Messages Bridge runs WhatsApp, Signal and Instagram on the person's own phone and serves their chats to Messages. It has
no inbox of its own: Messages binds to its service, logs in through it, reads chats and history from it and sends
through it (`CONTRACT.md`). The bridge's one screen shows each network's state.

## How it fits together

- **`BridgeService`** (`LegacyBridgeService` on API 29 and 30) is what Messages binds. Every call passes `CallerCheck`
  (Messages' package and certificate), then goes to the hub.
- **`Hub`** is the core, the same for every network. It owns each network's store and connector, turns what connectors
  report into stored events, pushes them to Messages' listener or wakes Messages with a broadcast, queues sends, and
  decides when a network stays connected.
- **A connector** (`Connector`) speaks one network's protocol and nothing else: login steps, connect, send, read
  receipts, logout. It reports chats, messages, media and state to the hub through `ConnectorSink`. A network registers
  its connector in `Networks`.
- **Storage per network:** everything a network has lives in `noBackupFilesDir/networks/<id>/`: the connector's own
  state (keys, sessions), `bridge.db` with chats, history and sends, `media/` and `outbox/`. Logging out deletes the
  folder. `events.db` beside it holds the unacked events of all networks. Nothing is backed up or moved to a new phone
  (`data_extraction_rules.xml`): the folder holds keys that would let anyone read and send as the person.
- **The status page** (`StatusActivity`) shows each network's state, whether Messages is installed and T's, and whether
  the notification wake is allowed, with a button to allow it. Logging in happens in Messages.

## Staying connected

No permanent foreground service. A network connects:

- while Messages holds a listener (Messages on screen);
- for 45 seconds when the network's own app on the phone posts a notification (`NetworkNotifications`, a notification
  listener the person allows on the status page). The bridge reads only which app posted, never the text;
- for up to 90 seconds every half hour, when the phone is online (`CatchUp`, WorkManager), until the networks go quiet,
  to catch what no notification announced;
- for a minute after `connect()`, a send or a read receipt, and five minutes after a login (history sync).

Otherwise it disconnects and shows as asleep. Muted chats wait for the catch-up.

## WhatsApp

A linked device on [whatsmeow](https://github.com/tulir/whatsmeow), as in Messages' spike
(`docs/spikes/wa-phone-bridge.md` there). The package `networks/whatsapp` wraps it, and `WhatsAppConnector` speaks to
it. Events cross as JSON (`events.go`, `WhatsAppEvents.kt` and `GoEvents.kt`, which reads the chats and messages of
every Go network).

- **Login:** `phone` asks for the number, then shows the 8-character code the person types into WhatsApp under Linked
  devices; `qr` shows a QR code that renews until scanned. The device shows in WhatsApp as "Chrome (Linux)" from
  "Messages".
- **Receiving:** text, images, video, audio, voice notes, files and stickers, with replies' targets. Live media
  downloads to `media/` after its message; media from history sync isn't downloaded. Reactions, edits and deletes are
  skipped for now. A person's chat takes their contact or push name; history sync brings chats with their unread counts
  and recent messages.
- **Sending:** text, and images, video, audio, voice notes and files up to 100 MB. WhatsApp doesn't echo what this device
  sends, so the connector adds the sent message to history itself.
- **Read:** read receipts go out for others' messages; reads on the person's other devices clear the chat here.
- **Store:** whatsmeow's keys and sessions in `wa.db` (pure-Go SQLite, modernc), in the network's folder.

## Signal

A linked device on signalmeow, the Signal client inside [mautrix-signal](https://github.com/mautrix/signal), used
without its Matrix layer (`DECISIONS.md`, Signal). The package `networks/signalbridge` wraps it, and `SignalConnector`
speaks to it, the same way as WhatsApp. signalmeow does Signal's cryptography through libsignal, Signal's Rust library,
which `networks/libsignal.sh` builds for each ABI at the version mautrix-signal needs
(`signalbridge/libsignal.version`).

- **Login:** `qr` only, as Signal allows. Messages shows the code and the person scans it with Signal on their phone,
  under Settings › Linked devices › Link a new device. The code renews every 45 seconds, up to 20 times. The device
  shows in Signal as "Messages".
  **Limitation:** the code has to be scanned by Signal on another phone. Signal on the phone that shows the code can't
  scan its own screen, and its `sgnl://linkdevice` link drops the code it carries. So a person whose Signal account
  lives on this same phone can't link it here; the bridge's Signal is for an account whose main phone is a different
  one.
- **History:** while linking, Signal asks the person whether to transfer their message history. If they agree, the
  phone uploads an archive, and the bridge waits for it (up to an hour, while connected) and adds each chat's newest
  1,000 messages as history; media in it isn't downloaded. If they don't, chats start with what arrives live.
- **Receiving:** text, long texts (over 2 KiB, which Signal sends as an attachment), images, video, audio, voice notes,
  files and stickers, with replies' targets. A message with several attachments arrives as one message per attachment,
  the text on the first. Contacts, polls and payments show as a line saying what they are. Reactions, edits, deletes,
  calls and view-once opens are skipped for now. A person's chat takes their name from the person's contacts or their
  Signal profile; a group its title.
- **Sending:** text, replies, and images, video, audio, voice notes and files up to 100 MB. Signal plays voice notes only
  as AAC, so a voice note in another format arrives as audio. Signal doesn't echo what this device sends, so the
  connector adds the sent message to history itself.
- **Read:** reading up to a message sends read receipts to each sender for every unread message before it; the bridge
  keeps the others' unread messages in `bridge_unread` for that. Reads on the person's other devices clear the chat
  here.
- **Store:** signalmeow's keys, sessions and contacts in `signal.db`, in the network's folder.

## Instagram

The person's own Instagram web session on instameow, the Instagram client inside
[mautrix-meta](https://github.com/mautrix/meta), used without its Matrix layer (`DECISIONS.md`, Instagram). The
package `networks/instagram` wraps it, and `InstagramConnector` speaks to it. Instagram's DMs aren't end-to-end
encrypted: Meta removed the opt-in encryption on 8 May 2026, so the chats show as not encrypted.

- **Login:** `cookies`. Messages opens Instagram's login page (`https://www.instagram.com/accounts/login/`) in its
  WebView, where Instagram itself handles two-factor codes and checks, and once the page reaches the inbox or feed
  (`waitForUrl`) sends its cookies: `sessionid`, `csrftoken` and `ds_user_id`, and `rur`, `shbid`, `shbts`, `mid` and
  `ig_did` when set, the same as Messages' server bridge. The bridge checks them by loading the inbox once.
- **Chats:** the inbox's first page arrives at login, with each person's name and handle or each group's title. A chat
  first heard of later is looked up when its first message comes. No history of messages: the inbox carries only stubs
  of them.
- **Receiving:** text, photos, videos, voice notes, GIFs and stickers, and replies' targets. An album shows its first
  photo or video with a line saying how many more. View-once photos and videos stay in Instagram; shared posts, reels
  and links show as a line with their title and link. Reactions, edits, unsends and group changes are skipped: the
  contract has no reactions yet.
- **Sending:** text, replies, photos, videos and voice notes up to 100 MB; audio goes as a voice note, files can't go
  (Instagram doesn't take them), and a caption follows the media as its own text. Instagram echoes what this device
  sends; the hub keeps the connector's own copy.
- **Read:** reading up to a message tells Instagram (and so the other side); reads on the person's other devices clear
  the chat here.
- **Logout** forgets the session on the phone. Instagram keeps it until it expires or the person ends it under
  Accounts Center › Password and security › Where you're logged in, as with mautrix-meta.
- **Store:** the session's cookies, the client's position in Instagram's event stream, and each thread's three ids
  (Instagram's calls each want a different one) in `instagram.db`, in the network's folder.

## Licences

The app is AGPL-3.0-only; `android/contract` is MIT so Messages can copy it. Every Go module linked into the app:

| Module | Licence |
|---|---|
| go.mau.fi/whatsmeow, go.mau.fi/util | MPL-2.0 |
| go.mau.fi/libsignal | GPL-3.0 |
| go.mau.fi/mautrix-signal, go.mau.fi/mautrix-meta | AGPL-3.0 |
| maunium.net/go/mautrix, go.mau.fi/zeroconfig | MPL-2.0 (mautrix-meta's Instagram client imports a few of mautrix-go's types) |
| github.com/beeper/argo-go, beeper/req (a fork of imroc/req), andybalholm/brotli, dustin/go-humanize, elliotchance/orderedmap, icholy/digest, mattn/go-colorable, mattn/go-isatty, quic-go/quic-go, quic-go/qpack, rs/xid, rs/zerolog, tidwall/gjson, tidwall/match, tidwall/pretty, tidwall/sjson, vektah/gqlparser, yuin/goldmark, gopkg.in/natefinch/lumberjack.v2, gopkg.in/yaml.v3 | MIT |
| github.com/coder/websocket | ISC |
| github.com/google/uuid, google/go-querystring, beeper/poly1305, klauspost/compress, refraction-networking/utls, remyoudompheng/bigfft, filippo.io/edwards25519, golang.org/x/*, google.golang.org/protobuf, modernc.org/* | BSD-3 |
| github.com/petermattis/goid, apache/thrift, google/flatbuffers, coreos/go-systemd, google.golang.org/grpc, google.golang.org/genproto | Apache-2.0 |

libsignal (`signalapp/libsignal`, AGPL-3.0) is linked as a static library, with its Rust crates and BoringSSL; their
licences are listed in its `acknowledgments` folder. GPL-3.0 and AGPL-3.0 code may be combined (section 13 of each).
Android libraries: AndroidX (Apache-2.0).
