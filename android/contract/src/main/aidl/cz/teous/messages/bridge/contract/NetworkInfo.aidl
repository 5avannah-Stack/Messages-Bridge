// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.LoginFlow;

/** A network the bridge offers. Network ids are lower-case words that never change: "whatsapp", "signal", "instagram". */
parcelable NetworkInfo {
    const int CAP_MEDIA = 1;            // send() takes media
    const int CAP_HISTORY = 2;          // history() can reach past what arrived live
    const int CAP_READ_RECEIPTS = 4;    // markRead() tells the other side
    const int CAP_END_TO_END = 8;       // chats are end to end encrypted all the way to this phone
    const int CAP_GROUPS = 16;

    String id;
    String name;
    LoginFlow[] loginFlows;
    int capabilities;
    /** The largest file send() takes, in bytes; 0 when the network takes no media. */
    long maxMediaBytes;
}
