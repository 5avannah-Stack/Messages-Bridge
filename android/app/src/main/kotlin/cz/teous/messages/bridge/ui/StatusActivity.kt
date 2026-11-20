package cz.teous.messages.bridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.teous.liquid.LiquidTheme
import cz.teous.liquid.NoirTokens
import cz.teous.messages.bridge.R
import cz.teous.messages.bridge.contract.BridgeContract
import cz.teous.messages.bridge.hub
import cz.teous.messages.bridge.wake.NetworkNotifications

/** Whether Messages is here, and whether it's T's (the bridge talks only to Messages signed with his key). */
enum class MessagesApp { Missing, Foreign, Ready }

/** The bridge's one screen: each network's state, Messages, and the notification wake (docs/OVERVIEW.md). */
class StatusActivity : ComponentActivity() {
    private var messages by mutableStateOf(MessagesApp.Missing)
    private var wakeGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always black, so the bars' icons are always light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        preferFastestRefreshRate()
        val hub = hub
        setContent {
            LiquidTheme(NoirTokens) {
                StatusScreen(
                    hub = hub,
                    messages = messages,
                    wakeGranted = wakeGranted,
                    onAllowWake = ::openWakeSettings,
                    onSource = { startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.source_url).toUri())) },
                )
            }
        }
    }

    // Both can change in Settings or TStore while the page is away.
    override fun onResume() {
        super.onResume()
        messages = messagesApp(this)
        wakeGranted = NetworkNotifications.granted(this)
    }

    private fun openWakeSettings() {
        val me = ComponentName(this, NetworkNotifications::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        startActivity(intent)
    }

    /** Every animation at the screen's fastest rate (phone-apps android.md, Refresh rate). */
    private fun preferFastestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val now = display?.mode ?: return
        val fastest = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
    }

    companion object {
        fun messagesApp(context: Context): MessagesApp {
            val pm = context.packageManager
            try {
                pm.getPackageInfo(BridgeContract.MESSAGES_PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return MessagesApp.Missing
            }
            val trusted = context.resources.getStringArray(R.array.trusted_callers)
            return if (trusted.any { BridgeContract.signedWith(pm, BridgeContract.MESSAGES_PACKAGE, it) }) MessagesApp.Ready else MessagesApp.Foreign
        }
    }
}
