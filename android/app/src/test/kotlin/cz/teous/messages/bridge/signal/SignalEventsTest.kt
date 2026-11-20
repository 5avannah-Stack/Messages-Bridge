package cz.teous.messages.bridge.signal

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.core.GoEvents
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The JSON here is what networks/signalbridge/events.go writes; its Go tests check that side. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SignalEventsTest {
    private val friend = "00000000-0000-4000-8000-000000000002"

    @Test
    fun groupMessageWithItsSecondAttachment() {
        val m = GoEvents.message(BridgeContract.SIGNAL, JSONObject("""{"id":"$friend|1800000000001|1","chat":"${"A".repeat(43)}=",
            "sender":"$friend","senderPhone":"10000000002","fromMe":false,"timestamp":1800000000001,"pushName":"Synthetic Friend",
            "kind":"image","mime":"image/jpeg","size":15,"media":true,"history":false}"""))
        assertEquals(BridgeContract.SIGNAL, m.network)
        assertEquals("$friend|1800000000001|1", m.id)
        assertEquals(Message.KIND_IMAGE, m.kind)
        assertEquals(Message.MEDIA_PENDING, m.mediaState)
        assertEquals("10000000002", m.senderPhone)
    }

    @Test
    fun personChatIsEndToEnd() {
        val c = GoEvents.chat(BridgeContract.SIGNAL, JSONObject("""{"id":"$friend","name":"Synthetic Friend","phone":"10000000002","group":false,"lastMs":5,"unread":-1}"""), endToEnd = true)
        assertEquals(Chat.TYPE_DIRECT, c.type)
        assertEquals(-1, c.unread)
        assertTrue(c.endToEnd)
    }

    @Test
    fun states() {
        assertEquals(AccountState.CONNECTED, SignalEvents.state(JSONObject("""{"state":"connected"}""")))
        assertEquals(AccountState.BAD_CREDENTIALS, SignalEvents.state(JSONObject("""{"state":"logged_out"}""")))
        assertEquals(AccountState.UNKNOWN_ERROR, SignalEvents.state(JSONObject("""{"state":"error","reason":"synthetic"}""")))
        assertEquals(AccountState.TRANSIENT_DISCONNECT, SignalEvents.state(JSONObject("""{"state":"disconnected"}""")))
    }
}
