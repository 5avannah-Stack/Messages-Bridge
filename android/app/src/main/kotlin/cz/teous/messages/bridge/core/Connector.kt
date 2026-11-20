package cz.teous.messages.bridge.core

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import java.io.File

/**
 * One network, as the hub sees it. A connector speaks its network's protocol and nothing else: the hub keeps chats,
 * history, events and the connection policy (docs/OVERVIEW.md). Network calls block; the hub calls them off the main
 * thread. A new network adds a connector and one line in [Networks].
 */
interface Connector {
    val info: NetworkInfo

    /** Apps whose notifications mean this network has news, such as com.whatsapp. */
    val wakePackages: Set<String>

    val isLoggedIn: Boolean

    /** The first step of [flowId]. Later steps of a DISPLAY step go to [ConnectorSink.login] with the same [loginId]. */
    fun startLogin(flowId: String, loginId: String): LoginStep
    fun submitLogin(loginId: String, stepId: String, values: Map<String, String>): LoginStep
    fun cancelLogin(loginId: String)

    fun connect()
    fun disconnect()

    /** Logs out on the network. The hub wipes the network's folder afterwards. */
    fun logout()

    /** Sends and returns the network's id for the message. */
    fun send(chatId: String, out: Outgoing): String

    /** Read receipts for [message] and everything before it in [chatId]. */
    fun markRead(chatId: String, message: Message)

    fun close()
}

/** A message on its way out; [file] is the bridge's own copy of the media. */
data class Outgoing(
    val localId: String,
    val text: String?,
    val kind: Int,
    val file: File?,
    val mime: String?,
    val fileName: String?,
    val durationS: Int,
    val replyToId: String?,
)

/** What a connector reports. Every call may come from any thread. */
interface ConnectorSink {
    fun state(state: AccountState)

    /** A chat's details. Empty fields keep what's stored; unread < 0 keeps the stored count. */
    fun chat(chat: Chat)

    /** A message, live or from history. Repeats are ignored. */
    fun message(message: Message)

    /** The media of a message is saved at [file], or failed when [file] is null. */
    fun media(chatId: String, messageId: String, file: File?)

    /** The person read [chatId] up to [messageId] on another device. */
    fun readElsewhere(chatId: String, messageId: String)

    fun login(step: LoginStep)
}

/** Builds a network's connector over its own folder. */
fun interface ConnectorFactory {
    fun create(dir: File, sink: ConnectorSink): Connector
}
