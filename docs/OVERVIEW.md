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

## Licences

The app is AGPL-3.0-only; `android/contract` is MIT so Messages can copy it. Its dependencies keep their own licences,
all compatible with it; the list grows with each network.
