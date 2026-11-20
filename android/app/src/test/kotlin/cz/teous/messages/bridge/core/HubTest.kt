package cz.teous.messages.bridge.core

import android.app.Application
import android.os.ParcelFileDescriptor
import org.robolectric.RuntimeEnvironment
import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Event
import cz.teous.messages.bridge.contract.IBridgeListener
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.SendRequest
import cz.teous.messages.bridge.contract.SendResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Everything is synthetic: +1 000 numbers, made-up names, a network that talks to nobody. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HubTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private lateinit var fake: FakeConnector
    private val wakes = AtomicInteger()
    private val hubs = mutableListOf<Hub>()

    @Before
    fun setUp() {
        root = File(context.cacheDir, "hub-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        hubs.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun hub(paired: Boolean = true): Hub {
        // Paired before the hub opens, as a store left by an earlier run; a logout's fresh connector isn't.
        if (paired) File(root, "networks/${FakeConnector.ID}").apply { mkdirs() }.resolve("paired").writeText("1")
        val factory = ConnectorFactory { dir, sink -> FakeConnector(dir, sink).also { fake = it } }
        return Hub(context, root, mapOf(FakeConnector.ID to factory), Executors.newSingleThreadExecutor(), Executors.newSingleThreadScheduledExecutor(), { wakes.incrementAndGet() })
            .also { hubs += it }
    }

    private fun message(id: String, timeMs: Long, fromMe: Boolean = false, history: Boolean = false) = Message().apply {
        chatId = CHAT
        this.id = id
        senderId = if (fromMe) "10000000001@s.whatsapp.net" else CHAT
        this.fromMe = fromMe
        this.timeMs = timeMs
        kind = Message.KIND_TEXT
        text = "synthetic $id"
        this.history = history
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun messagesBecomeStoredEventsUntilAcked() {
        val hub = hub()
        fake.sink.message(message("a", 1_000))
        fake.sink.message(message("a", 1_000))   // a repeat
        fake.sink.message(message("b", 2_000, history = true))
        val events = hub.events(0, 100).filter { it.type == Event.TYPE_MESSAGE }
        assertEquals(listOf("a", "b"), events.map { it.message?.id })
        assertTrue(events.zipWithNext().all { (x, y) -> x.seq < y.seq })
        // Only the live one from them counts as unread.
        assertEquals(1, hub.chats(FakeConnector.ID, 0, 10).single().unread)
        // With nobody listening, Messages gets the data-free wake.
        waitFor("wake") { wakes.get() > 0 }
        hub.ack(events.last().seq)
        assertTrue(hub.events(0, 100).none { it.seq <= events.last().seq })
    }

    @Test
    fun eventsSurviveARestart() {
        hub().also { fake.sink.message(message("a", 1_000)) }.close()
        hubs.clear()
        val again = hub()
        assertEquals("a", again.events(0, 100).single { it.type == Event.TYPE_MESSAGE }.message?.id)
    }

    @Test
    fun listenerGetsPushesInOrderAndLoginSteps() {
        val hub = hub(paired = false)
        val got = CopyOnWriteArrayList<Event>()
        hub.setListener(object : IBridgeListener.Stub() {
            override fun onEvents(events: Array<Event>) {
                got.addAll(events)
            }
        })
        val step = hub.startLogin(FakeConnector.ID, "code")
        assertEquals(LoginStep.DISPLAY_CODE, step.displayType)
        fake.sink.login(LoginStep().apply { type = LoginStep.TYPE_COMPLETE; loginId = step.loginId; stepId = "done"; accountId = "10000000001" })
        repeat(120) { fake.sink.message(message("m$it", 1_000L + it)) }
        waitFor("pushes") { got.count { it.type == Event.TYPE_MESSAGE } == 120 }
        val login = got.single { it.type == Event.TYPE_LOGIN }
        assertEquals(0, login.seq)
        assertEquals(LoginStep.TYPE_COMPLETE, login.login?.type)
        val seqs = got.filter { it.seq > 0 }.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    @Test
    fun sendReportsSentAndFailed() {
        val hub = hub()
        val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = hub.send(SendRequest().apply {
            network = FakeConnector.ID
            chatId = CHAT
            localId = "local-1"
            text = "caption"
            kind = Message.KIND_IMAGE
            this.media = ParcelFileDescriptor.open(media, ParcelFileDescriptor.MODE_READ_ONLY)
            mime = "image/jpeg"
        })
        assertEquals("local-1", id)
        waitFor("sent") { sendResult(hub, "local-1") != null }
        val ok = sendResult(hub, "local-1")!!
        assertEquals(SendResult.SENT, ok.state)
        assertEquals("remote-local-1", ok.messageId)
        assertEquals(listOf<Byte>(1, 2, 3), fake.sent.single().second?.toList())

        fake.failSend = true
        hub.send(SendRequest().apply { network = FakeConnector.ID; chatId = CHAT; localId = "local-2"; text = "hi"; kind = Message.KIND_TEXT })
        waitFor("failed") { sendResult(hub, "local-2") != null }
        assertEquals(SendResult.FAILED, sendResult(hub, "local-2")?.state)
    }

    private fun sendResult(hub: Hub, localId: String) = hub.events(0, 500).firstOrNull { it.type == Event.TYPE_SEND && it.send?.localId == localId }?.send

    @Test(expected = IllegalArgumentException::class)
    fun mediaOverTheNetworksLimitIsRefused() {
        val hub = hub()
        val big = File(root, "big.bin").apply { writeBytes(ByteArray(2_000)) }
        hub.send(SendRequest().apply {
            network = FakeConnector.ID
            chatId = CHAT
            kind = Message.KIND_FILE
            media = ParcelFileDescriptor.open(big, ParcelFileDescriptor.MODE_READ_ONLY)
        })
    }

    @Test
    fun aSendCutShortByTheProcessDyingEndsFailed() {
        hub().also {
            NetworkStore(context, FakeConnector.ID, File(root, "networks/${FakeConnector.ID}")).use { store ->
                store.putSend(SendResult().apply { localId = "lost"; chatId = CHAT; state = NetworkStore.QUEUED; timeMs = 1 })
            }
        }.close()
        hubs.clear()
        val again = hub()
        val result = sendResult(again, "lost")
        assertEquals(SendResult.FAILED, result?.state)
        assertEquals("interrupted", result?.error)
    }

    @Test
    fun aWakeConnectsAndTheNetworkSleepsAfter() {
        val hub = hub()
        assertEquals(AccountState.SLEEPING, hub.accountStates().single().state)
        hub.wake(FakeConnector.ID, 200)
        waitFor("connected") { hub.accountStates().single().state == AccountState.CONNECTED }
        waitFor("asleep") { fake.calls.contains("disconnect") && hub.accountStates().single().state == AccountState.SLEEPING }
        assertEquals("10000000001", hub.accountStates().single().displayName)
    }

    @Test
    fun markReadClearsUnreadAndTellsTheNetwork() {
        val hub = hub()
        fake.sink.message(message("a", 1_000))
        hub.markRead(FakeConnector.ID, CHAT, "a")
        assertEquals(0, hub.chats(FakeConnector.ID, 0, 10).single().unread)
        waitFor("receipt") { fake.calls.contains("markRead a") }
    }

    @Test
    fun logoutWipesTheNetwork() {
        val hub = hub()
        fake.sink.chat(Chat().apply { id = CHAT; name = "Synthetic Friend"; type = Chat.TYPE_DIRECT; unread = -1 })
        fake.sink.message(message("a", 1_000))
        hub.logout(FakeConnector.ID)
        assertTrue(hub.chats(FakeConnector.ID, 0, 10).isEmpty())
        assertFalse(fake.isLoggedIn)
        assertEquals(AccountState.LOGGED_OUT, hub.accountStates().single().state)
        assertTrue(hub.events(0, 100).none { it.type == Event.TYPE_MESSAGE })
    }

    companion object {
        const val CHAT = "10000000002@s.whatsapp.net"
    }
}
