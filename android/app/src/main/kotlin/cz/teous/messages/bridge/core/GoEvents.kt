package cz.teous.messages.bridge.core

import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Message
import org.json.JSONObject

/**
 * Reads the chats and messages the Go networks (networks/whatsapp, signalbridge and instagram) write as JSON into the
 * contract's types. All write the same shape; each network's own events (state, login) stay in its connector.
 */
internal object GoEvents {
    fun chat(network: String, json: JSONObject, endToEnd: Boolean) = Chat().apply {
        this.network = network
        id = json.getString("id")
        name = json.text("name")
        type = if (json.optBoolean("group")) Chat.TYPE_GROUP else Chat.TYPE_DIRECT
        phone = json.text("phone")
        handle = json.text("handle")
        lastTimeMs = json.optLong("lastMs")
        unread = json.optInt("unread", -1)
        this.endToEnd = endToEnd
    }

    fun message(network: String, json: JSONObject) = Message().apply {
        this.network = network
        chatId = json.getString("chat")
        id = json.getString("id")
        senderId = json.text("sender")
        senderName = json.text("pushName")
        senderPhone = json.text("senderPhone")
        fromMe = json.optBoolean("fromMe")
        timeMs = json.getLong("timestamp")
        kind = kind(json.optString("kind"))
        text = json.text("text")
        mime = json.text("mime")
        fileName = json.text("fileName")
        size = json.optLong("size")
        durationS = json.optInt("seconds")
        mediaState = if (json.optBoolean("media")) Message.MEDIA_PENDING else Message.MEDIA_NONE
        history = json.optBoolean("history")
        replyToId = json.text("replyTo")
    }

    fun kind(name: String) = when (name) {
        "text" -> Message.KIND_TEXT
        "image" -> Message.KIND_IMAGE
        "video" -> Message.KIND_VIDEO
        "audio" -> Message.KIND_AUDIO
        "voice" -> Message.KIND_VOICE
        "file" -> Message.KIND_FILE
        "sticker" -> Message.KIND_STICKER
        else -> Message.KIND_OTHER
    }

    /** What SendMedia calls each kind; null for what the networks can't send as media. */
    fun mediaKind(kind: Int) = when (kind) {
        Message.KIND_IMAGE -> "image"
        Message.KIND_VIDEO -> "video"
        Message.KIND_AUDIO -> "audio"
        Message.KIND_VOICE -> "voice"
        Message.KIND_FILE -> "file"
        else -> null
    }

    private fun JSONObject.text(name: String): String? = (opt(name) as? String)?.ifEmpty { null }
}
