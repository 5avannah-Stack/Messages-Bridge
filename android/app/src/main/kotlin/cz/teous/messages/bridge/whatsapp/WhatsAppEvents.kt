package cz.teous.messages.bridge.whatsapp

import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.core.GoEvents
import org.json.JSONObject

/** Reads the JSON events networks/whatsapp/events.go writes into the contract's types. */
internal object WhatsAppEvents {
    fun chat(json: JSONObject) = GoEvents.chat(BridgeContract.WHATSAPP, json, endToEnd = true)

    fun message(json: JSONObject) = GoEvents.message(BridgeContract.WHATSAPP, json)

    /** The account's state from a "state" event; disconnects are the hub's to name (sleep or a lost connection). */
    fun state(json: JSONObject): Int = when (json.optString("state")) {
        "connected" -> AccountState.CONNECTED
        "logged_out" -> AccountState.BAD_CREDENTIALS
        "replaced" -> AccountState.UNKNOWN_ERROR
        else -> AccountState.TRANSIENT_DISCONNECT
    }

    fun jid(digits: String) = "$digits@s.whatsapp.net"
}
