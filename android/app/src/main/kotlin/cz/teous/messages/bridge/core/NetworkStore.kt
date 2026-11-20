package cz.teous.messages.bridge.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.HistoryPage
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.SendResult
import java.io.File

/**
 * One network's chats, history and sends, in `bridge.db` inside the network's folder, which also holds the
 * connector's own state and the media. Logging out deletes the whole folder.
 */
class NetworkStore(context: Context, val network: String, val dir: File) :
    SQLiteOpenHelper(context, File(dir, "bridge.db").path, null, 1) {

    val mediaDir = File(dir, "media").apply { mkdirs() }
    val outboxDir = File(dir, "outbox").apply { mkdirs() }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chat (
                id TEXT PRIMARY KEY, name TEXT, type INTEGER NOT NULL, phone TEXT, handle TEXT,
                last_ms INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, preview TEXT,
                e2ee INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE message (
                chat_id TEXT NOT NULL, id TEXT NOT NULL, sender_id TEXT, sender_name TEXT, sender_phone TEXT,
                from_me INTEGER NOT NULL, time_ms INTEGER NOT NULL, kind INTEGER NOT NULL, text TEXT, mime TEXT,
                file_name TEXT, size INTEGER NOT NULL DEFAULT 0, duration_s INTEGER NOT NULL DEFAULT 0,
                media_state INTEGER NOT NULL DEFAULT 0, media_path TEXT, history INTEGER NOT NULL DEFAULT 0,
                reply_to TEXT, local_id TEXT, PRIMARY KEY (chat_id, id))""",
        )
        db.execSQL("CREATE INDEX message_time ON message (chat_id, time_ms, id)")
        db.execSQL(
            """CREATE TABLE send (
                local_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, state INTEGER NOT NULL, message_id TEXT,
                error TEXT, time_ms INTEGER NOT NULL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        /** A send's state before SendResult's SENT or FAILED. */
        const val QUEUED = 0
    }

    /** Merges [chat] in: empty fields keep what's stored, a negative unread keeps the count. */
    fun putChat(chat: Chat) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = chat(chat.id)
            val v = ContentValues().apply {
                put("id", chat.id)
                put("name", chat.name?.ifEmpty { null } ?: old?.name)
                put("type", if (chat.type != 0) chat.type else old?.type ?: Chat.TYPE_DIRECT)
                put("phone", chat.phone?.ifEmpty { null } ?: old?.phone)
                put("handle", chat.handle?.ifEmpty { null } ?: old?.handle)
                put("last_ms", maxOf(chat.lastTimeMs, old?.lastTimeMs ?: 0))
                put("unread", if (chat.unread >= 0) chat.unread else old?.unread ?: 0)
                put("preview", chat.preview?.ifEmpty { null } ?: old?.preview)
                put("e2ee", if (chat.endToEnd || old?.endToEnd == true) 1 else 0)
            }
            db.insertWithOnConflict("chat", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun chat(id: String): Chat? =
        readableDatabase.rawQuery("SELECT * FROM chat WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.chat() else null }

    fun chats(offset: Int, limit: Int): List<Chat> =
        readableDatabase.rawQuery("SELECT * FROM chat ORDER BY last_ms DESC, id LIMIT ? OFFSET ?", arrayOf("$limit", "$offset"))
            .use { c -> buildList { while (c.moveToNext()) add(c.chat()) } }

    /**
     * Stores [m] unless it's already there, and moves its chat along: last activity, preview, and one more unread for
     * a live message from someone else. Returns false for a repeat.
     */
    fun putMessage(m: Message): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("chat_id", m.chatId)
                put("id", m.id)
                put("sender_id", m.senderId)
                put("sender_name", m.senderName)
                put("sender_phone", m.senderPhone)
                put("from_me", if (m.fromMe) 1 else 0)
                put("time_ms", m.timeMs)
                put("kind", m.kind)
                put("text", m.text)
                put("mime", m.mime)
                put("file_name", m.fileName)
                put("size", m.size)
                put("duration_s", m.durationS)
                put("media_state", m.mediaState)
                put("history", if (m.history) 1 else 0)
                put("reply_to", m.replyToId)
                put("local_id", m.localId)
            }
            if (db.insertWithOnConflict("message", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false
            // No upsert: Android 10 ships SQLite 3.22, which predates ON CONFLICT DO UPDATE.
            db.execSQL("INSERT OR IGNORE INTO chat (id, type) VALUES (?, ?)", arrayOf(m.chatId, Chat.TYPE_DIRECT))
            db.execSQL(
                "UPDATE chat SET unread = unread + ?, preview = CASE WHEN ? >= last_ms THEN ? ELSE preview END, " +
                    "last_ms = MAX(last_ms, ?) WHERE id = ?",
                arrayOf(if (!m.fromMe && !m.history) 1 else 0, m.timeMs, m.text, m.timeMs, m.chatId),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun message(chatId: String, id: String): Message? =
        readableDatabase.rawQuery("SELECT * FROM message WHERE chat_id = ? AND id = ?", arrayOf(chatId, id))
            .use { if (it.moveToFirst()) it.message() else null }

    /** Media's place once it's saved; null while it's pending or failed. */
    fun mediaFile(chatId: String, id: String): File? =
        readableDatabase.rawQuery("SELECT media_path FROM message WHERE chat_id = ? AND id = ? AND media_state = ?", arrayOf(chatId, id, "${Message.MEDIA_READY}"))
            .use { if (it.moveToFirst()) it.getString(0)?.let(::File) else null }

    fun setMedia(chatId: String, id: String, file: File?) {
        val v = ContentValues().apply {
            put("media_state", if (file != null) Message.MEDIA_READY else Message.MEDIA_FAILED)
            put("media_path", file?.path)
        }
        writableDatabase.update("message", v, "chat_id = ? AND id = ?", arrayOf(chatId, id))
    }

    /** Older than (beforeMs, beforeId), newest first; beforeMs 0 starts at the newest. */
    fun history(chatId: String, beforeMs: Long, beforeId: String?, limit: Int): HistoryPage {
        val rows = if (beforeMs == 0L) {
            readableDatabase.rawQuery(
                "SELECT * FROM message WHERE chat_id = ? ORDER BY time_ms DESC, id DESC LIMIT ?",
                arrayOf(chatId, "${limit + 1}"),
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT * FROM message WHERE chat_id = ? AND (time_ms < ? OR (time_ms = ? AND id < ?)) " +
                    "ORDER BY time_ms DESC, id DESC LIMIT ?",
                arrayOf(chatId, "$beforeMs", "$beforeMs", beforeId ?: "", "${limit + 1}"),
            )
        }.use { c -> buildList { while (c.moveToNext()) add(c.message()) } }
        return HistoryPage().apply {
            messages = rows.take(limit).toTypedArray()
            hasMore = rows.size > limit
        }
    }

    fun markRead(chatId: String) {
        writableDatabase.execSQL("UPDATE chat SET unread = 0 WHERE id = ?", arrayOf(chatId))
    }

    fun putSend(r: SendResult) {
        val v = ContentValues().apply {
            put("local_id", r.localId)
            put("chat_id", r.chatId)
            put("state", r.state)
            put("message_id", r.messageId)
            put("error", r.error)
            put("time_ms", r.timeMs)
        }
        writableDatabase.insertWithOnConflict("send", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun send(localId: String): SendResult? =
        readableDatabase.rawQuery("SELECT * FROM send WHERE local_id = ?", arrayOf(localId)).use { if (it.moveToFirst()) it.sendResult() else null }

    /** Sends still queued, such as those a killed process left behind. */
    fun queuedSends(): List<SendResult> =
        readableDatabase.rawQuery("SELECT * FROM send WHERE state = $QUEUED", null).use { c -> buildList { while (c.moveToNext()) add(c.sendResult()) } }

    private fun Cursor.str(name: String): String? = getString(getColumnIndexOrThrow(name))
    /** A NOT NULL column. */
    private fun Cursor.req(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

    private fun Cursor.chat() = Chat().also {
        it.network = network
        it.id = req("id")
        it.name = str("name")
        it.type = int("type")
        it.phone = str("phone")
        it.handle = str("handle")
        it.lastTimeMs = long("last_ms")
        it.unread = int("unread")
        it.preview = str("preview")
        it.endToEnd = int("e2ee") == 1
    }

    private fun Cursor.message() = Message().also {
        it.network = network
        it.chatId = req("chat_id")
        it.id = req("id")
        it.senderId = str("sender_id")
        it.senderName = str("sender_name")
        it.senderPhone = str("sender_phone")
        it.fromMe = int("from_me") == 1
        it.timeMs = long("time_ms")
        it.kind = int("kind")
        it.text = str("text")
        it.mime = str("mime")
        it.fileName = str("file_name")
        it.size = long("size")
        it.durationS = int("duration_s")
        it.mediaState = int("media_state")
        it.history = int("history") == 1
        it.replyToId = str("reply_to")
        it.localId = str("local_id")
    }

    private fun Cursor.sendResult() = SendResult().also {
        it.localId = req("local_id")
        it.chatId = req("chat_id")
        it.state = int("state")
        it.messageId = str("message_id")
        it.error = str("error")
        it.timeMs = long("time_ms")
    }
}
