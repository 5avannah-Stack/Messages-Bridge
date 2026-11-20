# The contract between Messages and the bridge

Messages binds to the bridge's service and does everything through one AIDL interface, `IBridge`, and one callback,
`IBridgeListener`. Both live in `android/contract`, with the parcelables they pass and `BridgeContract.kt`'s names and
keys. That module is MIT-licensed and holds only interface definitions and constants, so Messages copies it unchanged
and stays free of the bridge's GPL code. Contract version: **1**.

It is network-agnostic and modelled loosely on mautrix bridgev2's provisioning API: networks offer login flows, a login
walks through steps, and each network's account has a bridge state. WhatsApp, Signal and Instagram all fit it; a network
differs only in its flows, its capabilities and the ids it uses.

## Binding and trust

Both apps are T's, signed with different keys, so neither can rely on a plain `signature` permission.

| | API 31 and up | API 29 and 30 |
|---|---|---|
| Service | `BridgeService`, guarded by `PERMISSION_BIND` | `LegacyBridgeService`, open to bind |
| Permission | `signature\|knownSigner`, `knownCerts` = Messages' release certificate | (knownSigner doesn't exist) |
| In code, every call | calling uid is `cz.teous.messages` and signed with `MESSAGES_CERT_SHA256` | the same |

Exactly one of the two services is enabled, by an API-level resource, and both answer `ACTION_BIND`. The code check runs on
every call either way, with `PackageManager.hasSigningCertificate`, which also accepts a key Messages rotates to. A
caller that fails it gets `SecurityException`.

Messages, before binding:

1. Requests `PERMISSION_BIND` in its manifest (`<uses-permission>`), and adds `<queries><package android:name="cz.teous.messages.bridge"/></queries>`.
2. Checks `BridgeContract.isGenuineBridge(packageManager)`, so another app can't take the package name while the bridge
   isn't installed.
3. Binds `BridgeContract.bindIntent()` with `BIND_AUTO_CREATE`.
4. Calls `info()` and stops if `contractVersion` is older than the methods it needs, or its own version is under
   `minClientVersion`.

In callbacks Messages may check that `Binder.getCallingUid()` is the bridge's uid.

## Calls

Calls block and may wait on the network, so Messages makes them off the main thread. Errors arrive as exceptions:
`SecurityException` (not Messages), `IllegalArgumentException` (unknown network, bad input such as a malformed number),
`IllegalStateException` (not logged in, no such login), `UnsupportedOperationException` (the network can't do it).

| Call | What it does |
|---|---|
| `info()` | Contract version, oldest supported client, app version. |
| `networks()` | The networks this build offers: id, name, login flows, capabilities, largest media. |
| `accounts()` | One `AccountState` per network, logged in or not. |
| `startLogin(network, flowId)` | Starts a login and returns its first step. |
| `submitLogin(loginId, stepId, values)` | Answers a USER_INPUT or COOKIES step, one `String` per field id. Returns the next step. |
| `cancelLogin(loginId)` | Gives up a login. |
| `connect(network)` | Connects now, such as when Messages comes on screen. |
| `logout(network)` | Logs out on the network and wipes that account's data on the phone. |
| `chats(network, offset, limit)` | Chats, newest activity first. |
| `history(network, chatId, beforeTimeMs, beforeId, limit)` | One page of messages older than the anchor, newest first, and whether more exist. |
| `send(request)` | Queues a message, returns its `localId` at once; the outcome comes as a `TYPE_SEND` event. |
| `markRead(network, chatId, messageId)` | Read up to there, here and for the other side when the network allows. |
| `openMedia(network, chatId, messageId)` | A read-only `ParcelFileDescriptor` of the message's media once it's `MEDIA_READY`. Messages copies it into its own storage. |
| `setListener(listener)` | The one listener; `null` clears it. A listener whose process dies is cleared. |
| `events(afterSeq, limit)` | Stored events after `afterSeq`, oldest first. |
| `ack(seq)` | Drops events up to and including `seq`. |

### Login

A login is a series of `LoginStep`s, as bridgev2's:

- `TYPE_USER_INPUT`: draw `fields` and `submitLogin` the values.
- `TYPE_COOKIES`: open `url` in a WebView with `userAgent`, let the person log in on the network's page, collect each
  `CookieField` (a cookie on `domain`, a local-storage key or a request header), run `extractJs` if set, and
  `submitLogin` them.
- `TYPE_DISPLAY`: show `displayData` as a QR code (`DISPLAY_QR`) or a code to type on the other device (`DISPLAY_CODE`),
  or only the instructions (`DISPLAY_NOTHING`), and wait. The step changes by itself: a renewed QR code, success or
  failure arrive as `TYPE_LOGIN` events with the same `loginId`.
- `TYPE_COMPLETE`: logged in as `accountId`. `TYPE_FAILED`: `error` says why; start again.

WhatsApp offers `phone` (USER_INPUT for the number, then DISPLAY_CODE with the 8-character pairing code the person
types into WhatsApp under Linked devices) and `qr` (DISPLAY_QR, renewed every 20 seconds or so).

### Sending

`send` copies the media out of the `ParcelFileDescriptor` before it returns, so Messages can close its end. Messages
shows the message as sending at once (`teodor-taste`, Sending feels instant), then marks it sent or failed when the
`TYPE_SEND` event with its `localId` arrives. The sent message also comes back as a `TYPE_MESSAGE` event carrying that
`localId`. A send interrupted by the bridge being killed ends as `FAILED` when the bridge next starts.

## Events

Everything the bridge hears becomes an `Event` with a `seq` that grows by one across all networks and survives
restarts. The bridge stores each one until `ack` passes it, so nothing is lost while Messages isn't running.

| Type | Set field | Meaning |
|---|---|---|
| `TYPE_ACCOUNT` | `account` | An account's state changed. |
| `TYPE_CHAT` | `chat` | A chat is new or changed (name, unread, last activity). |
| `TYPE_MESSAGE` | `message` | A message arrived, live, from another device of the person's, or from history sync (`history`). |
| `TYPE_MEDIA` | `message` | A message's `mediaState` changed. |
| `TYPE_SEND` | `send` | A `send` ended. |
| `TYPE_READ` | `chatId`, `messageId` | The chat was read up to there on another device. |
| `TYPE_LOGIN` | `login` | A login's next step. |

Delivery:

- **Messages on screen:** it binds, sets a listener and gets events pushed as they happen. It acks what it has stored.
- **Messages not running:** the bridge sends `ACTION_EVENTS` to `cz.teous.messages` by package, with no extras. Messages'
  receiver binds (through its application context, or from a short job), reads `events(lastSeq, …)`, notifies, acks and
  unbinds. The broadcast carries nothing, so a spoofed one only makes Messages look.
- Messages keeps the last `seq` it stored and starts every read from it, so a repeat is harmless.

## Staying connected

The bridge holds a network's connection while Messages is bound with a listener, and for a short window after a wake:
the network's own app posting a notification (the bridge's notification listener), the periodic catch-up, or
`connect()`. Then it disconnects, and the account shows `SLEEPING`. There is no permanent foreground service.
Messages should unbind when it leaves the screen and its work is done, or the bridge stays connected.

## Changing the contract

- **Adding** a method (at the end of `IBridge`), a field (at the end of a parcelable), a constant or an event type:
  raise `IBridge.VERSION`. Structured parcelables carry their size, so either app reads a newer or older parcelable,
  skipping fields it doesn't know and leaving missing ones at their defaults. A method the bridge doesn't have fails
  silently, which is why Messages checks `info().contractVersion` first.
- **Never** reorder, remove or retype a method or a field, or reuse a constant's value.
- **Breaking** changes get a new interface (`IBridge2`) and bind action beside the old ones, kept until every Messages in
  TStore uses the new one. `minClientVersion` lets the bridge send an old Messages to update.
- A change lands in this repo and in Messages' copy (`android/app/src/main/aidl` and the Kotlin file) on the same day.
