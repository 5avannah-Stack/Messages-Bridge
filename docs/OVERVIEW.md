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
(`docs/spikes/wa-phone-bridge.md` there). The Go module in `networks/whatsapp` wraps it; Gradle builds it into an AAR
with gomobile (`whatsappAar`), and `WhatsAppConnector` speaks to it. Events cross as JSON (`events.go`,
`WhatsAppEvents.kt`).

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

## Licences

The app is AGPL-3.0-only; `android/contract` is MIT so Messages can copy it. Every Go module linked into the app:

| Module | Licence |
|---|---|
| go.mau.fi/whatsmeow, go.mau.fi/util | MPL-2.0 |
| go.mau.fi/libsignal | GPL-3.0 |
| github.com/beeper/argo-go, dustin/go-humanize, elliotchance/orderedmap, mattn/go-colorable, mattn/go-isatty, rs/zerolog, vektah/gqlparser | MIT |
| github.com/coder/websocket | ISC |
| github.com/google/uuid, remyoudompheng/bigfft, filippo.io/edwards25519, golang.org/x/*, google.golang.org/protobuf, modernc.org/* | BSD-3 |
| github.com/petermattis/goid | Apache-2.0 |

GPL-3.0 and AGPL-3.0 code may be combined (section 13 of each). Android libraries: AndroidX (Apache-2.0).
