package cz.teous.messages.bridge.core

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.LoginFlow
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import java.io.File
import java.util.Collections

/** A network that talks to nobody: tests drive it through [sink] and read what the hub asked of it. */
class FakeConnector(val dir: File, val sink: ConnectorSink) : Connector {
    override val info = NetworkInfo().apply {
        id = ID
        name = "Fake"
        loginFlows = arrayOf(LoginFlow().apply { id = "code"; name = "Code" })
        capabilities = NetworkInfo.CAP_MEDIA
        maxMediaBytes = 1_000
    }
    override val wakePackages = setOf("org.example.fake")
    override var isLoggedIn = File(dir, "paired").exists()
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val sent: MutableList<Pair<Outgoing, ByteArray?>> = Collections.synchronizedList(mutableListOf())
    var failSend = false

    override fun startLogin(flowId: String, loginId: String) = LoginStep().apply {
        type = LoginStep.TYPE_DISPLAY
        this.loginId = loginId
        stepId = "show"
        network = ID
        displayType = LoginStep.DISPLAY_CODE
        displayData = "ABCD-EFGH"
    }

    override fun submitLogin(loginId: String, stepId: String, values: Map<String, String>) = throw IllegalStateException("nothing to submit")

    override fun cancelLogin(loginId: String) {
        calls += "cancelLogin"
    }

    override fun connect() {
        calls += "connect"
        sink.state(AccountState().apply { state = AccountState.CONNECTED; displayName = "10000000001" })
    }

    override fun disconnect() {
        calls += "disconnect"
        sink.state(AccountState().apply { state = AccountState.TRANSIENT_DISCONNECT })
    }

    override fun logout() {
        calls += "logout"
        isLoggedIn = false
    }

    override fun send(chatId: String, out: Outgoing): String {
        if (failSend) throw java.io.IOException("offline")
        sent += out to out.file?.readBytes()
        return "remote-${out.localId}"
    }

    override fun markRead(chatId: String, message: Message) {
        calls += "markRead ${message.id}"
    }

    override fun close() = Unit

    companion object {
        const val ID = "fake"
    }
}
