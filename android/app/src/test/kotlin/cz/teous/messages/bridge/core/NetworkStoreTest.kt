package cz.teous.messages.bridge.core

import android.app.Application
import org.robolectric.RuntimeEnvironment
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.cacheDir, "store-${System.nanoTime()}").apply { mkdirs() }
    private val store = NetworkStore(context, "fake", dir)

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun put(id: String, timeMs: Long) = store.putMessage(Message().apply {
        chatId = "c"
        this.id = id
        this.timeMs = timeMs
        kind = Message.KIND_TEXT
        text = id
    })

    @Test
    fun historyPagesNewestFirstWithTies() {
        put("a", 1)
        put("b", 2)
        put("c", 2)
        put("d", 3)
        val first = store.history("c", 0, null, 2)
        assertEquals(listOf("d", "c"), first.messages.map { it.id })
        assertTrue(first.hasMore)
        val last = first.messages.last()
        val second = store.history("c", last.timeMs, last.id, 2)
        assertEquals(listOf("b", "a"), second.messages.map { it.id })
        assertFalse(second.hasMore)
    }

    @Test
    fun chatMergeKeepsWhatAnUpdateLeavesOut() {
        store.putChat(Chat().apply { id = "c"; name = "Synthetic Group"; type = Chat.TYPE_GROUP; unread = 3; lastTimeMs = 10 })
        store.putChat(Chat().apply { id = "c"; unread = -1; lastTimeMs = 5 })
        val chat = store.chat("c")!!
        assertEquals("Synthetic Group", chat.name)
        assertEquals(Chat.TYPE_GROUP, chat.type)
        assertEquals(3, chat.unread)
        assertEquals(10, chat.lastTimeMs)
    }

    @Test
    fun aMessageMovesItsChatOn() {
        put("a", 100)
        val chat = store.chat("c")!!
        assertEquals(100, chat.lastTimeMs)
        assertEquals("a", chat.preview)
        assertEquals(1, chat.unread)
        assertFalse(put("a", 100))
    }
}
