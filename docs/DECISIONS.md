# Decisions

## Settled by T

1. **A separate, open-source app** (Messages DECISIONS 39). WhatsApp, Signal and Instagram run on the person's phone in Messages Bridge, published under AGPL-3.0 at `github.com/5avannah-Stack/Messages-Bridge`, because their libraries are GPL-3.0 or AGPL-3.0 and bundling them would open Messages' source. Messages stays closed, keeps every screen and talks to this app on the phone. Telegram stays inside Messages on TDLib. T's own chats run on his server's bridges; this app is for everyone else, and is T's standby when his server is down.
2. **Installed with Messages.** TStore lists it in the same folder as Messages; people install both.
3. **The contract** (`CONTRACT.md`). Messages binds one AIDL service, network-agnostic and modelled on bridgev2's provisioning, with stored events that survive either app being killed. Messages is trusted by its signing certificate: a `knownSigner` permission on API 31 and up, and a check in code on every call on all versions.

## Open

- Encrypting each network's store at rest (SQLCipher or a Keystore-wrapped key). Android's file-based encryption covers a locked phone; a store copied off an unlocked, rooted phone would let someone read and send as the person.

## Defaults until told otherwise

- `android/contract` is MIT, not AGPL, so Messages can copy it without its source opening; it holds only interfaces and constants.
- Package `cz.teous.messages.bridge`, `minSdk` 29, with its own signing key.
- One branch, `main`, and full releases, since Messages' betas don't need a bridge beta yet.
- Noir, as Messages.
