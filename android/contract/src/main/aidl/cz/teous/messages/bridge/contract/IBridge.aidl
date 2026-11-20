// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.AccountState;
import cz.teous.messages.bridge.contract.BridgeInfo;
import cz.teous.messages.bridge.contract.Chat;
import cz.teous.messages.bridge.contract.Event;
import cz.teous.messages.bridge.contract.HistoryPage;
import cz.teous.messages.bridge.contract.IBridgeListener;
import cz.teous.messages.bridge.contract.LoginStep;
import cz.teous.messages.bridge.contract.NetworkInfo;
import cz.teous.messages.bridge.contract.SendRequest;

/**
 * The bridge's service, bound with BridgeContract.ACTION_BIND. Methods are append only: each one keeps its place, and
 * a new one goes at the end with VERSION raised. Every call checks the caller first and throws SecurityException for
 * anyone but Messages. Bad input throws IllegalArgumentException, a call in the wrong state IllegalStateException, a
 * network that can't do it UnsupportedOperationException. Calls may block on the network: never on the main thread.
 */
interface IBridge {
    const int VERSION = 2;

    BridgeInfo info();
    NetworkInfo[] networks();
    /** One per network this build offers, logged in or not. */
    AccountState[] accounts();

    LoginStep startLogin(String network, String flowId);
    /** Answers a USER_INPUT or COOKIES step: values hold one String per field id. Returns the next step. */
    LoginStep submitLogin(String loginId, String stepId, in Bundle values);
    void cancelLogin(String loginId);
    /** Connects now, for when Messages opens; the bridge decides when to let go again. */
    void connect(String network);
    /** Logs out on the network and wipes the account's data on this phone. */
    void logout(String network);

    /** Chats, newest activity first. */
    Chat[] chats(String network, int offset, int limit);
    /** Messages older than (beforeTimeMs, beforeId), newest first; 0 and null for the newest. */
    HistoryPage history(String network, String chatId, long beforeTimeMs, @nullable String beforeId, int limit);
    /** Queues a message and returns its localId at once; the outcome follows as an Event of TYPE_SEND. */
    String send(in SendRequest request);
    /** Marks the chat read up to messageId, here and, when the network allows it, for the other side. */
    void markRead(String network, String chatId, String messageId);
    /** The message's media, read-only, once its mediaState is READY. */
    ParcelFileDescriptor openMedia(String network, String chatId, String messageId);

    /** The one listener, or null to clear it. A listener that dies is cleared. */
    void setListener(@nullable IBridgeListener listener);
    /** Events after afterSeq, oldest first, at most limit. */
    Event[] events(long afterSeq, int limit);
    /** Drops every event up to and including seq. */
    void ack(long seq);
}
