package cz.teous.messages.bridge.signal

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.LoginFlow
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import cz.teous.messages.bridge.core.Connector
import cz.teous.messages.bridge.core.ConnectorSink
import cz.teous.messages.bridge.core.GoEvents
import cz.teous.messages.bridge.core.Outgoing
import cz.teous.messages.bridge.go.signalbridge.Bridge
import cz.teous.messages.bridge.go.signalbridge.Signalbridge
import org.json.JSONObject
import java.io.File

/**
 * Signal as a linked device, on signalmeow (networks/signalbridge). Signal links only by QR code, scanned with Signal on the
 * person's phone, so this phone shows the code and another phone scans it: Signal on this same phone can't scan its
 * own screen (docs/OVERVIEW.md, Signal). signalmeow keeps the keys in `signal.db` in this network's folder; chats and
 * history go to the hub.
 */
class SignalConnector(private val dir: File, private val sink: ConnectorSink) : Connector {
    private val go: Bridge = Signalbridge.open(dir.path) { kind, payload -> onEvent(kind, JSONObject(payload)) }
    @Volatile private var loginId: String? = null

    override val info = NetworkInfo().apply {
        id = BridgeContract.SIGNAL
        name = "Signal"
        loginFlows = arrayOf(
            LoginFlow().apply { id = FLOW_QR; name = "QR code"; description = "Scan with Signal on your phone" },
        )
        capabilities = NetworkInfo.CAP_MEDIA or NetworkInfo.CAP_READ_RECEIPTS or NetworkInfo.CAP_END_TO_END or NetworkInfo.CAP_GROUPS
        maxMediaBytes = 100L * 1024 * 1024
    }

    /** Signal, and Molly, the fork some people use instead. */
    override val wakePackages = setOf("org.thoughtcrime.securesms", "im.molly.app")

    override val isLoggedIn: Boolean get() = go.isLinked

    override fun startLogin(flowId: String, loginId: String): LoginStep {
        require(flowId == FLOW_QR) { "unknown login flow $flowId" }
        this.loginId = loginId
        return qrStep(loginId, go.loginQR(), QR_RENEW_MS)
    }

    override fun submitLogin(loginId: String, stepId: String, values: Map<String, String>): LoginStep =
        throw IllegalArgumentException("nothing to submit at $stepId")

    override fun cancelLogin(loginId: String) {
        if (loginId == this.loginId) {
            this.loginId = null
            go.cancelLogin()
        }
    }

    override fun connect() = go.connect()
    override fun disconnect() = go.disconnect()
    override fun logout() = go.logout()
    override fun close() {
        go.close()
    }

    override fun send(chatId: String, out: Outgoing): String {
        val file = out.file
        val reply = out.replyToId ?: ""
        val id = if (file == null) {
            go.sendText(chatId, out.text ?: "", reply)
        } else {
            val kind = GoEvents.mediaKind(out.kind) ?: throw UnsupportedOperationException("Signal can't send this kind")
            go.sendMedia(chatId, file.path, kind, out.mime ?: "application/octet-stream", out.text ?: "", out.fileName ?: "", out.durationS.toLong(), reply)
        }
        // Signal doesn't echo what this device sent, so the sent message goes into history from here.
        sink.message(Message().apply {
            network = BridgeContract.SIGNAL
            this.chatId = chatId
            this.id = id
            senderId = go.account().ifEmpty { null }
            fromMe = true
            timeMs = System.currentTimeMillis()
            kind = if (file == null) Message.KIND_TEXT else out.kind
            text = out.text
            mime = out.mime
            fileName = out.fileName
            size = file?.length() ?: 0
            durationS = out.durationS
            mediaState = if (file == null) Message.MEDIA_NONE else Message.MEDIA_PENDING
            replyToId = out.replyToId
            localId = out.localId
        })
        if (file != null) {
            val kept = File(dir, "media/sent-${id.filter(Char::isLetterOrDigit)}")
            file.copyTo(kept, overwrite = true)
            sink.media(chatId, id, kept)
        }
        return id
    }

    override fun markRead(chatId: String, message: Message) {
        if (message.fromMe) return
        go.markRead(chatId, message.id)
    }

    private fun onEvent(kind: String, json: JSONObject) {
        when (kind) {
            "state" -> sink.state(account(SignalEvents.state(json), json.optString("reason").ifEmpty { null }))
            "paired" -> {
                loginId?.let { id -> sink.login(step(id, LoginStep.TYPE_COMPLETE, "done").apply { accountId = json.getString("account") }) }
                loginId = null
            }
            "qr" -> loginId?.let { sink.login(qrStep(it, json.getString("code"), json.optLong("timeoutMs"))) }
            "qr_done" -> failLogin(json.optString("result"))
            "chat" -> sink.chat(GoEvents.chat(BridgeContract.SIGNAL, json, endToEnd = true))
            "message" -> sink.message(GoEvents.message(BridgeContract.SIGNAL, json))
            "media" -> sink.media(json.getString("chat"), json.getString("id"), (json.opt("path") as? String)?.let(::File))
            "read" -> sink.readElsewhere(json.getString("chat"), json.getString("id"))
        }
    }

    private fun failLogin(error: String) {
        val id = loginId ?: return
        loginId = null
        sink.login(step(id, LoginStep.TYPE_FAILED, "failed").apply { this.error = error.ifEmpty { "login failed" } })
    }

    private fun account(state: Int, error: String?) = AccountState().apply {
        network = BridgeContract.SIGNAL
        this.state = state
        accountId = go.account().ifEmpty { null }
        displayName = go.number().takeIf { it.isNotEmpty() }?.let { "+$it" }
        this.error = error
        sinceMs = System.currentTimeMillis()
    }

    private fun step(loginId: String, type: Int, stepId: String) = LoginStep().apply {
        this.type = type
        this.loginId = loginId
        this.stepId = stepId
        network = BridgeContract.SIGNAL
    }

    private fun qrStep(loginId: String, code: String, timeoutMs: Long) = step(loginId, LoginStep.TYPE_DISPLAY, STEP_QR).apply {
        displayType = LoginStep.DISPLAY_QR
        displayData = code
        instructions = "Signal › Settings › Linked devices › Link a new device, on another phone"
        expiresAtMs = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0
    }

    companion object {
        const val FLOW_QR = "qr"
        const val STEP_QR = "qr"
        private const val QR_RENEW_MS = 45_000L
    }
}
