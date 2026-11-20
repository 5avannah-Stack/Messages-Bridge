package cz.teous.messages.bridge.whatsapp

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Message
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The JSON here is what networks/whatsapp/events.go writes; its Go tests check that side. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WhatsAppEventsTest {
    @Test
    fun liveVoiceNoteWaitsForItsMedia() {
        val m = WhatsAppEvents.message(JSONObject("""{"id":"V1","chat":"120363000000000001@g.us","sender":"10000000002@s.whatsapp.net",
            "senderPhone":"10000000002","fromMe":false,"timestamp":1800000000000,"pushName":"Synthetic Friend","kind":"voice",
            "mime":"audio/ogg; codecs=opus","seconds":4,"media":true,"history":false}"""))
        assertEquals(Message.KIND_VOICE, m.kind)
        assertEquals(Message.MEDIA_PENDING, m.mediaState)
        assertEquals(4, m.durationS)
        assertEquals("Synthetic Friend", m.senderName)
        assertEquals("10000000002", m.senderPhone)
        assertNull(m.text)
        assertFalse(m.history)
    }

    @Test
    fun historyTextAndUnknownKinds() {
        val m = WhatsAppEvents.message(JSONObject("""{"id":"H1","chat":"10000000002@s.whatsapp.net","sender":"","fromMe":true,
            "timestamp":1700000000000,"kind":"text","text":"from before","media":false,"history":true,"replyTo":"A1"}"""))
        assertEquals(Message.KIND_TEXT, m.kind)
        assertEquals(Message.MEDIA_NONE, m.mediaState)
        assertTrue(m.history)
        assertTrue(m.fromMe)
        assertNull(m.senderId)
        assertEquals("A1", m.replyToId)
        assertEquals(Message.KIND_OTHER, WhatsAppEvents.kind("poll"))
    }

    @Test
    fun chatsKeepTheirUnreadUnlessTold() {
        val live = WhatsAppEvents.chat(JSONObject("""{"jid":"10000000002@s.whatsapp.net","name":"Synthetic Friend","phone":"10000000002","group":false,"lastMs":5,"unread":-1}"""))
        assertEquals(-1, live.unread)
        assertEquals(Chat.TYPE_DIRECT, live.type)
        assertEquals("10000000002", live.phone)
        assertTrue(live.endToEnd)
        val group = WhatsAppEvents.chat(JSONObject("""{"jid":"120363000000000001@g.us","group":true,"lastMs":0,"unread":3}"""))
        assertEquals(Chat.TYPE_GROUP, group.type)
        assertEquals(3, group.unread)
        assertNull(group.name)
    }

    @Test
    fun statesAndMediaKinds() {
        assertEquals(AccountState.CONNECTED, WhatsAppEvents.state(JSONObject("""{"state":"connected"}""")))
        assertEquals(AccountState.BAD_CREDENTIALS, WhatsAppEvents.state(JSONObject("""{"state":"logged_out","reason":"401"}""")))
        assertEquals(AccountState.TRANSIENT_DISCONNECT, WhatsAppEvents.state(JSONObject("""{"state":"disconnected"}""")))
        assertEquals("voice", WhatsAppEvents.mediaKind(Message.KIND_VOICE))
        assertNull(WhatsAppEvents.mediaKind(Message.KIND_STICKER))
    }
}
