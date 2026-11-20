# Messages Bridge

An Android app that runs WhatsApp, Signal and Instagram on your own phone and hands the chats to
[Messages](https://github.com/5avannah-Stack/Messages), so they sit in one inbox with your texts. Nothing goes through
anyone's server: the bridge talks to each network straight from the phone, the way a linked device or a web client does.

Messages shows everything; this app has only a status page. Install both from TStore.

## Why a separate app

The network libraries it needs are copyleft: WhatsApp's whatsmeow needs `go.mau.fi/libsignal` (GPL-3.0), Signal's
libsignal and mautrix-signal, and mautrix-meta's Instagram code are AGPL-3.0. They live here, in the open, and Messages talks to this app
through a small bound service (`docs/CONTRACT.md`). Only interface definitions cross between the two.

## Layout

| Path | What |
|---|---|
| `android/contract` | The contract: AIDL interfaces, parcelables and constants. Messages copies this module. |
| `android/app` | The bridge app: the service, per-network storage, the wake strategy and the status page. |
| `networks` | The Go module bound into the app with gomobile: `whatsapp` on whatsmeow, `signal` on mautrix-signal's signalmeow, and `libsignal.sh`, which builds Signal's Rust library. |
| `docs` | `CONTRACT.md`, `OVERVIEW.md`, `DECISIONS.md`, `CFLS.md`. |

## Building

You need JDK 21, the Android SDK with NDK 27 and CMake, Go 1.27, rustup, protoc and libclang (for libsignal's BoringSSL bindings). The Gradle build compiles libsignal
and the Go module itself.

```sh
cd networks && ./libsignal.sh host && go test ./...
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## Licence

GNU Affero General Public License v3.0 only (`LICENSE`), except `android/contract`, which is MIT
(`android/contract/LICENSE`) so that Messages can copy it. Each Go dependency keeps its own licence; see
`docs/OVERVIEW.md`, Licences.
