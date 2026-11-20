// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** A network's account on this phone, with bridgev2's state names plus SLEEPING. */
parcelable AccountState {
    const int LOGGED_OUT = 0;
    const int CONNECTING = 1;
    const int CONNECTED = 2;
    /** Logged in and disconnected on purpose to save battery; it wakes on the network's notification or catch-up. */
    const int SLEEPING = 3;
    /** Lost the connection; the bridge retries by itself. */
    const int TRANSIENT_DISCONNECT = 4;
    /** The network ended the session (unlinked, password changed). Log in again. */
    const int BAD_CREDENTIALS = 5;
    const int UNKNOWN_ERROR = 6;

    String network;
    int state;
    /** The network's own id for the account, such as WhatsApp's 10005550100@s.whatsapp.net. */
    @nullable String accountId;
    /** What the person knows the account by: a number, a username. */
    @nullable String displayName;
    @nullable String error;
    long sinceMs;
}
