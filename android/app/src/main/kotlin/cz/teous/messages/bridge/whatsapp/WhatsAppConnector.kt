package cz.teous.messages.bridge.whatsapp

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.LoginField
import cz.teous.messages.bridge.contract.LoginFlow
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import cz.teous.messages.bridge.core.Connector
import cz.teous.messages.bridge.core.ConnectorSink
import cz.teous.messages.bridge.core.Outgoing
import cz.teous.messages.bridge.whatsapp.go.whatsapp.Bridge
import cz.teous.messages.bridge.whatsapp.go.whatsapp.Whatsapp
import org.json.JSONObject
import java.io.File

/**
 * WhatsApp as a linked device, on whatsmeow (networks/whatsapp). Two login flows: "phone", where the person types the
 * pairing code into WhatsApp on their main phone, and "qr", where they scan a code. whatsmeow keeps the keys in
 * `wa.db` in this network's folder; chats and history go to the hub.
 */
class WhatsAppConnector(private val dir: File, private val sink: ConnectorSink) : Connector {
    private val go: Bridge = Whatsapp.open(dir.path) { kind, payload -> onEvent(kind, JSONObject(payload)) }
    @Volatile private var loginId: String? = null

    override val info = NetworkInfo().apply {
        id = BridgeContract.WHATSAPP
        name = "WhatsApp"
        loginFlows = arrayOf(
            LoginFlow().apply { id = FLOW_PHONE; name = "Phone number"; description = "A code to type into WhatsApp on your phone" },
            LoginFlow().apply { id = FLOW_QR; name = "QR code"; description = "Scan with WhatsApp on your phone" },
        )
        capabilities = NetworkInfo.CAP_MEDIA or NetworkInfo.CAP_READ_RECEIPTS or NetworkInfo.CAP_END_TO_END or NetworkInfo.CAP_GROUPS
        maxMediaBytes = 100L * 1024 * 1024
    }

    override val wakePackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override val isLoggedIn: Boolean get() = go.isPaired

    override fun startLogin(flowId: String, loginId: String): LoginStep {
        this.loginId = loginId
        return when (flowId) {
            FLOW_PHONE -> step(loginId, LoginStep.TYPE_USER_INPUT, STEP_PHONE).apply {
                fields = arrayOf(LoginField().apply {
                    type = LoginField.TYPE_PHONE
                    id = FIELD_PHONE
                    name = "Phone number"
                    description = "Your WhatsApp number, with the country code"
                    pattern = "\\+?[0-9 ()-]{7,20}"
                })
            }
            FLOW_QR -> qrStep(loginId, go.loginQR(), 0)
            else -> throw IllegalArgumentException("unknown login flow $flowId")
        }
    }

    override fun submitLogin(loginId: String, stepId: String, values: Map<String, String>): LoginStep {
        check(loginId == this.loginId) { "no such login" }
        require(stepId == STEP_PHONE) { "nothing to submit at $stepId" }
        val phone = values[FIELD_PHONE] ?: throw IllegalArgumentException("no phone number")
        val code = try {
            go.pairPhone(phone)
        } catch (e: Exception) {
            // whatsmeow's own text, such as the number being malformed; never the number itself.
            throw IllegalArgumentException(e.message ?: "pairing failed")
        }
        return step(loginId, LoginStep.TYPE_DISPLAY, STEP_CODE).apply {
            displayType = LoginStep.DISPLAY_CODE
            displayData = code
            instructions = "WhatsApp › Linked devices › Link a device › Link with phone number instead"
            expiresAtMs = System.currentTimeMillis() + 160_000
        }
    }

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
        val id = if (file == null) {
            go.sendText(chatId, out.text ?: "")
        } else {
            val kind = WhatsAppEvents.mediaKind(out.kind) ?: throw UnsupportedOperationException("WhatsApp can't send this kind")
            go.sendMedia(chatId, file.path, kind, out.mime ?: "application/octet-stream", out.text ?: "", out.fileName ?: "", out.durationS.toLong())
        }
        // WhatsApp doesn't echo what this device sent, so the sent message goes into history from here.
        sink.message(Message().apply {
            network = BridgeContract.WHATSAPP
            this.chatId = chatId
            this.id = id
            senderId = go.account().takeIf { it.isNotEmpty() }?.let(WhatsAppEvents::jid)
            fromMe = true
            timeMs = System.currentTimeMillis()
            kind = if (file == null) Message.KIND_TEXT else out.kind
            text = out.text
            mime = out.mime
            fileName = out.fileName
            size = file?.length() ?: 0
            durationS = out.durationS
            mediaState = if (file == null) Message.MEDIA_NONE else Message.MEDIA_PENDING
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
        val sender = if (chatId.endsWith("@g.us")) message.senderId ?: "" else ""
        go.markRead(chatId, sender, message.id, message.timeMs)
    }

    private fun onEvent(kind: String, json: JSONObject) {
        when (kind) {
            "state" -> sink.state(account(WhatsAppEvents.state(json), json.optString("reason").ifEmpty { null }))
            "paired" -> {
                loginId?.let { id -> sink.login(step(id, LoginStep.TYPE_COMPLETE, "done").apply { accountId = WhatsAppEvents.jid(json.getString("account")) }) }
                loginId = null
            }
            "pair_failed" -> failLogin(json.optString("error"))
            "qr" -> loginId?.let { sink.login(qrStep(it, json.getString("code"), json.optLong("timeoutMs"))) }
            "qr_done" -> failLogin(json.optString("result"))
            "chat" -> sink.chat(WhatsAppEvents.chat(json))
            "message" -> sink.message(WhatsAppEvents.message(json))
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
        network = BridgeContract.WHATSAPP
        this.state = state
        val me = go.account()
        if (me.isNotEmpty()) {
            accountId = WhatsAppEvents.jid(me)
            displayName = "+$me"
        }
        this.error = error
        sinceMs = System.currentTimeMillis()
    }

    private fun step(loginId: String, type: Int, stepId: String) = LoginStep().apply {
        this.type = type
        this.loginId = loginId
        this.stepId = stepId
        network = BridgeContract.WHATSAPP
    }

    private fun qrStep(loginId: String, code: String, timeoutMs: Long) = step(loginId, LoginStep.TYPE_DISPLAY, STEP_QR).apply {
        displayType = LoginStep.DISPLAY_QR
        displayData = code
        instructions = "WhatsApp › Linked devices › Link a device"
        expiresAtMs = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0
    }

    companion object {
        const val FLOW_PHONE = "phone"
        const val FLOW_QR = "qr"
        const val STEP_PHONE = "phone"
        const val STEP_CODE = "code"
        const val STEP_QR = "qr"
        const val FIELD_PHONE = "phone"
    }
}
