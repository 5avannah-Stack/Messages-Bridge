// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/**
 * A message to send. With media, the bridge copies the file before send() returns, so Messages may close its end.
 */
parcelable SendRequest {
    String network;
    String chatId;
    /** Messages' own id for the message, echoed in the result and the sent message; the bridge makes one when null. */
    @nullable String localId;
    /** The text, or the media's caption. */
    @nullable String text;
    /** Message.KIND_*; TEXT without media. */
    int kind;
    @nullable ParcelFileDescriptor media;
    @nullable String mime;
    @nullable String fileName;
    int durationS;
    @nullable String replyToId;
}
