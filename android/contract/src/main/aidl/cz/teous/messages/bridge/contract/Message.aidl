// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** One message. Media isn't inside: Messages reads it with openMedia() once mediaState is READY. */
parcelable Message {
    const int KIND_TEXT = 1;
    const int KIND_IMAGE = 2;
    const int KIND_VIDEO = 3;
    const int KIND_AUDIO = 4;
    const int KIND_VOICE = 5;
    const int KIND_FILE = 6;
    const int KIND_STICKER = 7;
    /** Something this bridge can't show yet; text says what, when it can. */
    const int KIND_OTHER = 8;

    const int MEDIA_NONE = 0;
    const int MEDIA_PENDING = 1;
    const int MEDIA_READY = 2;
    const int MEDIA_FAILED = 3;

    String network;
    String chatId;
    String id;
    @nullable String senderId;
    @nullable String senderName;
    @nullable String senderPhone;
    boolean fromMe;
    long timeMs;
    int kind;
    @nullable String text;
    @nullable String mime;
    @nullable String fileName;
    long size;
    int durationS;
    int mediaState;
    /** It came from the network's history sync rather than live, so Messages shouldn't notify about it. */
    boolean history;
    @nullable String replyToId;
    /** For a message sent through send(), the localId it was sent with. */
    @nullable String localId;
}
