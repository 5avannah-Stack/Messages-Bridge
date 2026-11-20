# Decisions

## Settled by T

1. **A separate, open-source app** (Messages DECISIONS 39). WhatsApp, Signal and Instagram run on the person's phone in Messages Bridge, published under AGPL-3.0 at `github.com/5avannah-Stack/Messages-Bridge`, because their libraries are GPL-3.0 or AGPL-3.0 and bundling them would open Messages' source. Messages stays closed, keeps every screen and talks to this app on the phone. Telegram stays inside Messages on TDLib. T's own chats run on his server's bridges; this app is for everyone else, and is T's standby when his server is down.
2. **Installed with Messages.** TStore lists it in the same folder as Messages; people install both.
3. **The contract** (`CONTRACT.md`). Messages binds one AIDL service, network-agnostic and modelled on bridgev2's provisioning, with stored events that survive either app being killed. Messages is trusted by its signing certificate: a `knownSigner` permission on API 31 and up, and a check in code on every call on all versions.

## Open

- Whether the bridge hides WhatsApp's (and later Signal's and Instagram's) own notifications once Messages shows the same message. It doesn't now: the notification listener only reads which app posted.
- An opt-in "always connected" mode (a `remoteMessaging` foreground service) for people without the network's app on the phone. Not built.
- Encrypting each network's store at rest (SQLCipher or a Keystore-wrapped key). Android's file-based encryption covers a locked phone; a store copied off an unlocked, rooted phone would let someone read and send as the person.

## Defaults until told otherwise

- `android/contract` is MIT, not AGPL, so Messages can copy it without its source opening; it holds only interfaces and constants.
- Package `cz.teous.messages.bridge`, `minSdk` 29, with its own signing key.
- One branch, `main`, and full releases, since Messages' betas don't need a bridge beta yet.
- Noir, as Messages.
- WhatsApp's store uses modernc's pure-Go SQLite, which builds and tests the same everywhere; mattn's cgo SQLite is smaller (about 0.9 MB) and stays a build tag (`cgosqlite`) until its tests run on the PC.
- Media from WhatsApp's history sync isn't downloaded; only live media is.
- **Signal on signalmeow**, mautrix-signal's Go client, without its Matrix layer, rather than libsignal's Java bindings
  with a Signal service client. signalmeow is a whole client (linking, Signal's two sockets, sealed sender, groups,
  attachments, contact and storage sync, the history transfer) kept current with Signal's servers by whatsmeow's
  maintainer, so the connector is the same thin Go wrapper as WhatsApp's, in the same gomobile AAR and Go runtime.
  The Java route has libsignal's Android library but no maintained service client to go with it: Signal-Android's
  isn't published, and signal-cli's fork is unofficial and built for desktops, so the bridge would own the key stores,
  the receive loop and every protocol change. The cost: libsignal's Rust is cross-compiled for each ABI
  (`networks/libsignal.sh`, needing rustup, protoc, CMake and libclang), at the version mautrix-signal pins, and the
  release APK grows from 8.4 to 16.3 MB.
- **Instagram on instameow**, the Instagram client in mautrix-meta (`pkg/instameow`, with messagix's cookies, sockets
  and HTTP client), without its Matrix layer. mautrix-meta dropped messagix's Instagram mode in mid-2026 for this new
  client, so it is the maintained one. It still imports a few of mautrix-go's types (MPL-2.0), which get linked but
  aren't used here. The login takes the cookies of Instagram's own page, as Messages' server bridge does, rather than
  a password flow, so Instagram handles two-factor codes and checks itself. That page's "logged in" URL pattern needed
  a contract field (`LoginStep.waitForUrl`, contract version 2). Reactions aren't shown: the contract has no
  reactions yet, and adding them is a change in Messages too. The release APK grows from 16.3 to 20.8 MB.
- Signal links only by a QR code scanned with Signal on another phone (`OVERVIEW.md`, Signal), so the bridge's Signal
  serves accounts whose main phone isn't the one running Messages.
- Staying connected (from the WhatsApp spike): wake on the network's own notification, a half-hourly catch-up, and connected while Messages is on screen; no permanent foreground service.
