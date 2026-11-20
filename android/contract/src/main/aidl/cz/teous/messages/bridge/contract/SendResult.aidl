// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** How a send() ended, as an Event of TYPE_SEND. */
parcelable SendResult {
    const int SENT = 1;
    const int FAILED = 2;

    String localId;
    String chatId;
    int state;
    /** The network's id for the sent message. */
    @nullable String messageId;
    @nullable String error;
    long timeMs;
}
