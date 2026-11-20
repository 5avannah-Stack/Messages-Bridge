package cz.teous.messages.bridge.core

/** The networks this build carries, by id. A network adds its connector here and nowhere else in the core. */
object Networks {
    val factories: Map<String, ConnectorFactory> = linkedMapOf()
}
