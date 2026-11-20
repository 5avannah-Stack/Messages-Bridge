package cz.teous.messages.bridge.whatsapp

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Message
import org.json.JSONObject

/** Reads the JSON events networks/whatsapp/events.go writes into the contract's types. */
internal object WhatsAppEvents {
    fun chat(json: JSONObject) = Chat().apply {
        network = BridgeContract.WHATSAPP
        id = json.getString("jid")
        name = json.text("name")
        type = if (json.optBoolean("group")) Chat.TYPE_GROUP else Chat.TYPE_DIRECT
        phone = json.text("phone")
        lastTimeMs = json.optLong("lastMs")
        unread = json.optInt("unread", -1)
        endToEnd = true
    }

    fun message(json: JSONObject) = Message().apply {
        network = BridgeContract.WHATSAPP
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

    /** What SendMedia calls each kind; null for what WhatsApp can't send as media. */
    fun mediaKind(kind: Int) = when (kind) {
        Message.KIND_IMAGE -> "image"
        Message.KIND_VIDEO -> "video"
        Message.KIND_AUDIO -> "audio"
        Message.KIND_VOICE -> "voice"
        Message.KIND_FILE -> "file"
        else -> null
    }

    /** The account's state from a "state" event; disconnects are the hub's to name (sleep or a lost connection). */
    fun state(json: JSONObject): Int = when (json.optString("state")) {
        "connected" -> AccountState.CONNECTED
        "logged_out" -> AccountState.BAD_CREDENTIALS
        "replaced" -> AccountState.UNKNOWN_ERROR
        else -> AccountState.TRANSIENT_DISCONNECT
    }

    fun jid(digits: String) = "$digits@s.whatsapp.net"

    private fun JSONObject.text(name: String): String? = (opt(name) as? String)?.ifEmpty { null }
}
