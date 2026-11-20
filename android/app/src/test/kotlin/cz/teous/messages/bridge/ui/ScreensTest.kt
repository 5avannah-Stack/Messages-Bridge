package cz.teous.messages.bridge.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.createBitmap
import cz.teous.liquid.LiquidTheme
import cz.teous.liquid.NoirTokens
import cz.teous.messages.bridge.R
import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.core.Hub
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Draws the status page with synthetic accounts to build/screens/*.png (build/screens/cs in Czech), so it can be looked
// at without a phone. Texts are checked through the app's strings, so ScreensCzechTest runs the same in Czech.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
open class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val res get() = compose.activity.resources

    private fun save(name: String) {
        val czech = res.configuration.locales[0].language == "cs"
        val dir = File(if (czech) "build/screens/cs" else "build/screens").apply { mkdirs() }
        // Compose's own capture waits for a frame Robolectric never sends, so the window is drawn by hand.
        val shot = compose.runOnIdle {
            val window = compose.activity.window.decorView
            createBitmap(window.width, window.height).also { window.draw(Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(accounts: List<AccountState>, messages: MessagesApp, wake: Boolean, attached: Boolean = false) {
        compose.setContent {
            LiquidTheme(NoirTokens) {
                StatusScreen(accounts, mapOf("whatsapp" to "WhatsApp", "signal" to "Signal", "instagram" to "Instagram"), attached, messages, wake, {}, {})
            }
        }
    }

    @Test
    fun status() {
        show(
            listOf(
                Hub.account("whatsapp", AccountState.CONNECTED).apply { displayName = "+1 000 000 0001" },
                Hub.account("signal", AccountState.SLEEPING).apply { displayName = "+1 000 000 0001" },
                Hub.account("instagram", AccountState.LOGGED_OUT),
            ),
            MessagesApp.Ready, wake = false, attached = true,
        )
        compose.onNodeWithText(res.getString(R.string.wake_allow)).assertExists()
        compose.onNodeWithText(res.getString(R.string.state_logged_out)).assertExists()
        save("status")
    }

    @Test
    fun problems() {
        show(listOf(Hub.account("whatsapp", AccountState.BAD_CREDENTIALS)), MessagesApp.Foreign, wake = true)
        compose.onNodeWithText(res.getString(R.string.messages_foreign)).assertExists()
        save("problems")
    }

    @Test
    fun empty() {
        show(emptyList(), MessagesApp.Missing, wake = true)
        compose.onNodeWithText(res.getString(R.string.no_networks)).assertExists()
        save("empty")
    }
}

@Config(sdk = [35], qualifiers = "cs-w411dp-h891dp-xxhdpi", application = Application::class)
class ScreensCzechTest : ScreensTest()
