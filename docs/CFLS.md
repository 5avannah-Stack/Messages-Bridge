# Changes from the latest release

No release yet.

- The project: repository, licence, agent instructions, metadata and docs.
- The contract between Messages and the bridge, version 1: the AIDL interfaces, parcelables and constants in `android/contract`, and `docs/CONTRACT.md`.
- The app: the bound service with its caller check, a core shared by every network (stored events, sends, per-network storage, the connection policy), the notification wake and half-hourly catch-up, and the status page in Noir, in Czech and English. No network yet.
- WhatsApp: log in with a pairing code or a QR code, connect, receive text and media, send text and media, chats and history from history sync, read receipts both ways, logout.
- Signal: link as a secondary device by QR code (scanned with Signal on another phone), connect, receive text and media,
  send text, replies and media, history from Signal's transfer when the person allows it, read receipts both ways,
  logout.
