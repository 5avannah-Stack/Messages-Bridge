// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.AccountState;
import cz.teous.messages.bridge.contract.Chat;
import cz.teous.messages.bridge.contract.LoginStep;
import cz.teous.messages.bridge.contract.Message;
import cz.teous.messages.bridge.contract.SendResult;

/**
 * Something that happened, in order. seq grows across all networks and survives restarts; the bridge keeps every
 * event until ack() passes it. Only the field for its type is set, read when the event is read, so it is the latest
 * state. A client skips types it doesn't know. TYPE_LOGIN events go only to the listener, with seq 0.
 */
parcelable Event {
    const int TYPE_ACCOUNT = 1;   // account
    const int TYPE_CHAT = 2;      // chat: new or changed
    const int TYPE_MESSAGE = 3;   // message: new, live or from history
    const int TYPE_MEDIA = 4;     // message: its mediaState changed
    const int TYPE_SEND = 5;      // send: how a send() ended
    const int TYPE_READ = 6;      // chatId, messageId: read up to there on another device
    const int TYPE_LOGIN = 7;     // login: a login's next step

    long seq;
    int type;
    String network;
    long timeMs;
    @nullable AccountState account;
    @nullable Chat chat;
    @nullable Message message;
    @nullable SendResult send;
    @nullable LoginStep login;
    @nullable String chatId;
    @nullable String messageId;
}
