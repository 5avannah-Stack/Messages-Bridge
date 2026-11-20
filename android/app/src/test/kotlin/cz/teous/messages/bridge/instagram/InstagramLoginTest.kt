package cz.teous.messages.bridge.instagram

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.core.GoEvents
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The login step Messages' WebView follows, and the JSON networks/instagram/events.go writes. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InstagramLoginTest {
    @Test
    fun cookiesStepWaitsForTheLoggedInPage() {
        val step = InstagramLogin.cookiesStep("login")
        assertEquals(LoginStep.TYPE_COOKIES, step.type)
        assertEquals("https://www.instagram.com/accounts/login/", step.url)
        val required = step.cookieFields!!.filter { it.required }.map { it.name }
        assertEquals(listOf("sessionid", "csrftoken", "ds_user_id"), required)
        assertTrue(step.cookieFields!!.all { it.domain == "instagram.com" })
        val done = Regex(step.waitForUrl!!)
        assertTrue(done.matches("https://www.instagram.com/"))
        assertTrue(done.matches("https://www.instagram.com/direct/inbox/"))
        assertTrue(done.matches("https://www.instagram.com/accounts/onetap/?next=%2F"))
        assertFalse(done.matches("https://www.instagram.com/accounts/login/"))
        assertFalse(done.matches("https://www.instagram.com/challenge/"))
    }

    @Test
    fun directChatHasAHandleAndNoEncryption() {
        val c = GoEvents.chat(BridgeContract.INSTAGRAM, JSONObject("""{"id":"340282366841710300949128100000000001","name":"Synthetic Friend",
            "handle":"synthetic.friend","group":false,"lastMs":5,"unread":-1}"""), endToEnd = false)
        assertEquals(Chat.TYPE_DIRECT, c.type)
        assertEquals("synthetic.friend", c.handle)
        assertFalse(c.endToEnd)
    }

    @Test
    fun voiceNoteWaitsForItsMedia() {
        val m = GoEvents.message(BridgeContract.INSTAGRAM, JSONObject("""{"id":"mid.synthetic1","chat":"340282366841710300949128100000000001",
            "sender":"100000000000002","fromMe":false,"timestamp":1800000000000,"pushName":"Synthetic Friend","kind":"voice",
            "mime":"audio/mp4","seconds":3,"media":true,"history":false,"replyTo":"mid.synthetic0"}"""))
        assertEquals(Message.KIND_VOICE, m.kind)
        assertEquals(Message.MEDIA_PENDING, m.mediaState)
        assertEquals("mid.synthetic0", m.replyToId)
    }

    @Test
    fun states() {
        assertEquals(AccountState.CONNECTED, InstagramLogin.state(JSONObject("""{"state":"connected"}""")))
        assertEquals(AccountState.BAD_CREDENTIALS, InstagramLogin.state(JSONObject("""{"state":"logged_out"}""")))
        assertEquals(AccountState.TRANSIENT_DISCONNECT, InstagramLogin.state(JSONObject("""{"state":"disconnected"}""")))
    }
}
