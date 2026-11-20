// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** A chat on a network. ids are the network's own and opaque to Messages. */
parcelable Chat {
    const int TYPE_DIRECT = 1;
    const int TYPE_GROUP = 2;
    const int TYPE_CHANNEL = 3;

    String network;
    String id;
    @nullable String name;
    int type;
    /** A direct chat's number, international digits without +, for linking to the contact (Messages DECISIONS 39). */
    @nullable String phone;
    /** A direct chat's username, such as an Instagram handle, for suggesting a contact. */
    @nullable String handle;
    long lastTimeMs;
    int unread;
    @nullable String preview;
    boolean endToEnd;
}
