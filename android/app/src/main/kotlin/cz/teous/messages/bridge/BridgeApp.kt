package cz.teous.messages.bridge

import android.app.Application
import android.content.Intent
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.core.Hub
import cz.teous.messages.bridge.core.Networks
import cz.teous.messages.bridge.wake.CatchUp
import java.util.concurrent.Executors

class BridgeApp : Application() {
    /** One hub for the process: the service, the wakes and the status page share it. */
    val hub: Hub by lazy {
        Hub(
            context = this,
            root = noBackupFilesDir,
            factories = Networks.factories,
            work = Executors.newFixedThreadPool(4),
            timer = Executors.newSingleThreadScheduledExecutor(),
            wakeMessages = { sendBroadcast(Intent(BridgeContract.ACTION_EVENTS).setPackage(BridgeContract.MESSAGES_PACKAGE)) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        CatchUp.schedule(this)
    }
}

val android.content.Context.hub: Hub get() = (applicationContext as BridgeApp).hub
