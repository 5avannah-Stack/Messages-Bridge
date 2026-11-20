package cz.teous.messages.bridge.signal

import cz.teous.messages.bridge.contract.AccountState
import org.json.JSONObject

/** Reads networks/signalbridge/events.go's own events; chats and messages go through GoEvents. */
internal object SignalEvents {
    /** The account's state from a "state" event; disconnects are the hub's to name (sleep or a lost connection). */
    fun state(json: JSONObject): Int = when (json.optString("state")) {
        "connected" -> AccountState.CONNECTED
        "logged_out" -> AccountState.BAD_CREDENTIALS
        "error" -> AccountState.UNKNOWN_ERROR
        else -> AccountState.TRANSIENT_DISCONNECT
    }
}
