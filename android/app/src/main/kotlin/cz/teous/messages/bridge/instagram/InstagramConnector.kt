package cz.teous.messages.bridge.instagram

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.CookieField
import cz.teous.messages.bridge.contract.LoginFlow
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import cz.teous.messages.bridge.core.Connector
import cz.teous.messages.bridge.core.ConnectorSink
import cz.teous.messages.bridge.core.GoEvents
import cz.teous.messages.bridge.core.Outgoing
import cz.teous.messages.bridge.go.instagram.Bridge
import cz.teous.messages.bridge.go.instagram.Instagram
import org.json.JSONObject
import java.io.File

/**
 * Instagram on instameow (networks/instagram), with the person's own web session: they log in on Instagram's page in
 * Messages' WebView, which handles two-factor codes and Meta's checks itself, and its cookies come here. Instagram's
 * DMs aren't end-to-end encrypted. The session lives in `instagram.db` in this network's folder; chats go to the hub.
 */
class InstagramConnector(private val dir: File, private val sink: ConnectorSink) : Connector {
    private val go: Bridge = Instagram.open(dir.path) { kind, payload -> onEvent(kind, JSONObject(payload)) }
    @Volatile private var loginId: String? = null

    override val info = NetworkInfo().apply {
        id = BridgeContract.INSTAGRAM
        name = "Instagram"
        loginFlows = arrayOf(
            LoginFlow().apply { id = FLOW_COOKIES; name = "Instagram login"; description = "Log in on Instagram's own page" },
        )
        capabilities = NetworkInfo.CAP_MEDIA or NetworkInfo.CAP_READ_RECEIPTS or NetworkInfo.CAP_GROUPS
        maxMediaBytes = 100L * 1024 * 1024
    }

    override val wakePackages = setOf("com.instagram.android", "com.instagram.lite")

    override val isLoggedIn: Boolean get() = go.isLoggedIn

    override fun startLogin(flowId: String, loginId: String): LoginStep {
        require(flowId == FLOW_COOKIES) { "unknown login flow $flowId" }
        this.loginId = loginId
        return InstagramLogin.cookiesStep(loginId)
    }

    override fun submitLogin(loginId: String, stepId: String, values: Map<String, String>): LoginStep {
        check(loginId == this.loginId) { "no such login" }
        require(stepId == InstagramLogin.STEP_COOKIES) { "nothing to submit at $stepId" }
        this.loginId = null
        return try {
            val account = go.loginCookies(JSONObject(values).toString())
            InstagramLogin.step(loginId, LoginStep.TYPE_COMPLETE, "done").apply { accountId = account }
        } catch (e: Exception) {
            // instameow's own words, such as a checkpoint Instagram wants passed first; never a cookie's value.
            InstagramLogin.step(loginId, LoginStep.TYPE_FAILED, "failed").apply { error = e.message ?: "login failed" }
        }
    }

    override fun cancelLogin(loginId: String) {
        if (loginId == this.loginId) this.loginId = null
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
            val kind = GoEvents.mediaKind(out.kind) ?: throw UnsupportedOperationException("Instagram can't send this kind")
            go.sendMedia(chatId, file.path, kind, out.mime ?: "application/octet-stream", out.text ?: "", out.fileName ?: "", out.durationS.toLong(), reply)
        }
        // Instagram echoes the message later; the hub keeps the first copy, this one, which carries the localId.
        sink.message(Message().apply {
            network = BridgeContract.INSTAGRAM
            this.chatId = chatId
            this.id = id
            senderId = null
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
        go.markRead(chatId, message.id, message.timeMs)
    }

    private fun onEvent(kind: String, json: JSONObject) {
        when (kind) {
            "state" -> sink.state(account(InstagramLogin.state(json), json.optString("reason").ifEmpty { null }))
            "chat" -> sink.chat(GoEvents.chat(BridgeContract.INSTAGRAM, json, endToEnd = false))
            "message" -> sink.message(GoEvents.message(BridgeContract.INSTAGRAM, json))
            "media" -> sink.media(json.getString("chat"), json.getString("id"), (json.opt("path") as? String)?.let(::File))
            "read" -> sink.readElsewhere(json.getString("chat"), json.getString("id"))
        }
    }

    private fun account(state: Int, error: String?) = AccountState().apply {
        network = BridgeContract.INSTAGRAM
        this.state = state
        accountId = go.account().ifEmpty { null }
        displayName = go.username().takeIf { it.isNotEmpty() }?.let { "@$it" }
        this.error = error
        sinceMs = System.currentTimeMillis()
    }

    companion object {
        const val FLOW_COOKIES = "cookies"
    }
}

/** Instagram's login step and events, apart from the connector so tests can read them without the Go library. */
internal object InstagramLogin {
    const val STEP_COOKIES = "cookies"
    const val URL = "https://www.instagram.com/accounts/login/"

    /** Where Instagram lands once the person is in: the feed, the inbox or a thread, or the one-tap prompt. */
    const val LOGGED_IN = "^https://www\\.instagram\\.com/(?:direct/(?:inbox/|t/[0-9]+/)?|accounts/onetap/)?(?:\\?.*)?$"

    /** The session itself, and what Instagram uses to route and trust it. As Messages' server bridge asks for. */
    private val REQUIRED = listOf("sessionid", "csrftoken", "ds_user_id")
    private val OPTIONAL = listOf("rur", "shbid", "shbts", "mid", "ig_did")

    fun cookiesStep(loginId: String) = step(loginId, LoginStep.TYPE_COOKIES, STEP_COOKIES).apply {
        url = URL
        waitForUrl = LOGGED_IN
        cookieFields = (REQUIRED.map { it to true } + OPTIONAL.map { it to false }).map { (cookie, needed) ->
            CookieField().apply {
                id = cookie
                required = needed
                source = CookieField.SOURCE_COOKIE
                name = cookie
                domain = "instagram.com"
            }
        }.toTypedArray()
        instructions = "Log in to Instagram"
    }

    fun step(loginId: String, type: Int, stepId: String) = LoginStep().apply {
        this.type = type
        this.loginId = loginId
        this.stepId = stepId
        network = BridgeContract.INSTAGRAM
    }

    /** The account's state from a "state" event; disconnects are the hub's to name (sleep or a lost connection). */
    fun state(json: JSONObject): Int = when (json.optString("state")) {
        "connected" -> AccountState.CONNECTED
        "logged_out" -> AccountState.BAD_CREDENTIALS
        "error" -> AccountState.UNKNOWN_ERROR
        else -> AccountState.TRANSIENT_DISCONNECT
    }
}
