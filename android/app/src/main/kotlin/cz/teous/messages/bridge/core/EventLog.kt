package cz.teous.messages.bridge.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The events Messages hasn't acked, across all networks, in order (docs/CONTRACT.md, Events). A row names what
 * happened and where; the hub fills in the chat, message or send from the network's store when Messages reads it,
 * so nothing is kept twice. AUTOINCREMENT keeps seq growing even after acked rows go.
 */
class EventLog(context: Context, file: File) : SQLiteOpenHelper(context, file.path, null, 1) {

    data class Row(val seq: Long, val type: Int, val network: String, val timeMs: Long, val chatId: String?, val messageId: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE event (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, network TEXT NOT NULL,
                time_ms INTEGER NOT NULL, chat_id TEXT, message_id TEXT)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Stores an event and returns its seq. For a send, [messageId] is the send's localId. */
    fun append(type: Int, network: String, timeMs: Long, chatId: String? = null, messageId: String? = null): Row {
        val v = ContentValues().apply {
            put("type", type)
            put("network", network)
            put("time_ms", timeMs)
            put("chat_id", chatId)
            put("message_id", messageId)
        }
        val seq = writableDatabase.insertOrThrow("event", null, v)
        return Row(seq, type, network, timeMs, chatId, messageId)
    }

    fun after(seq: Long, limit: Int): List<Row> =
        readableDatabase.rawQuery("SELECT seq, type, network, time_ms, chat_id, message_id FROM event WHERE seq > ? ORDER BY seq LIMIT ?", arrayOf("$seq", "$limit"))
            .use { c -> buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5))) } }

    fun ack(seq: Long) {
        writableDatabase.delete("event", "seq <= ?", arrayOf("$seq"))
    }

    /** A logged-out network's events go with its data. */
    fun dropNetwork(network: String) {
        writableDatabase.delete("event", "network = ?", arrayOf(network))
    }

    fun pending(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM event", null).use { it.moveToFirst(); it.getLong(0) }
}
