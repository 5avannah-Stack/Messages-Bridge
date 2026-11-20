package cz.teous.messages.bridge.core

import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.signal.SignalConnector
import cz.teous.messages.bridge.whatsapp.WhatsAppConnector

/** The networks this build carries, by id. A network adds its connector here and nowhere else in the core. */
object Networks {
    val factories: Map<String, ConnectorFactory> = linkedMapOf(
        BridgeContract.WHATSAPP to ConnectorFactory(::WhatsAppConnector),
        BridgeContract.SIGNAL to ConnectorFactory(::SignalConnector),
    )
}
