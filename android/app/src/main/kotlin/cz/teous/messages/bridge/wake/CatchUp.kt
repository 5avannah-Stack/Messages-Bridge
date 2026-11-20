package cz.teous.messages.bridge.wake

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import cz.teous.messages.bridge.hub
import java.util.concurrent.TimeUnit

/**
 * The periodic catch-up: every half hour, when the phone has a network, connect everything logged in, wait until the
 * networks go quiet, and let go. It catches what no notification announced, such as muted chats.
 */
class CatchUp(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val hub = applicationContext.hub
        val start = System.currentTimeMillis()
        hub.wake(null, MAX_MS)
        // Quiet means nothing new for QUIET_MS, but give the connection MIN_MS to come up and start talking first.
        while (true) {
            Thread.sleep(1_000)
            val t = System.currentTimeMillis()
            if (t - start >= MAX_MS) break
            if (t - start >= MIN_MS && t - maxOf(hub.lastActivity, start) >= QUIET_MS) break
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "catch-up"
        private const val MIN_MS = 15_000L
        private const val QUIET_MS = 10_000L
        private const val MAX_MS = 90_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatchUp>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
