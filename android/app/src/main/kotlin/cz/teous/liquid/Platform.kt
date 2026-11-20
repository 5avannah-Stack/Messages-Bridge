package cz.teous.liquid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext

/**
 * A recorded copy of what lies behind the glass. The glass draws it bent and blurred (API 31+); below that it has no
 * use, and the glass is opaque.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)

    init {
        // Kept as a picture: while only the glass moves, the screen and the glass both take the page from it, and the
        // page's own drawing, such as a list of lens-lit rows, isn't worked out again for either.
        layer.compositingStrategy = CompositingStrategy.Offscreen
    }
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Records the content it wraps for the glass above it. Wrap everything the glass should show through: the page, and
 * anything over it that sits under the glass, such as search's dim and results. The content gets a layer of its own,
 * so glass that moves above it redraws the glass only: without one, every frame of the glass would record the whole
 * page again.
 */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .graphicsLayer { }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * One frame of the display [view] is on, in ms. The first frame after a rest advances by this, since no earlier frame
 * time is known: a fixed 1/60 s would start every motion with a double step at 120 Hz.
 */
internal fun frameMs(view: View): Float = 1000f / (view.display?.refreshRate?.takeIf { it >= 1f } ?: 60f)

/** Android's Remove animations (animator duration scale 0) is the phone's Reduce Motion. */
@Composable
fun prefersReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(reducedMotion(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = reducedMotion(context) }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun reducedMotion(context: Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The dock's pulses as the phone's own semantic feedback (phone-apps android.md), not raw vibration lengths: the
 * system picks a waveform the motor plays well and honours the person's touch-feedback setting. The confirm cue is the
 * exception: Android's CONFIRM is a single click on most phones, so it plays as two short clicks, the double pulse the
 * web apps play ([18, 24, 18] ms), and nothing that follows within [HapticGate.HOLD_MS] cuts it short.
 */
object Haptics {
    private val gate = HapticGate()

    fun perform(view: View, kind: Haptic) {
        if (!gate.allow(kind, SystemClock.uptimeMillis())) return
        if (kind == Haptic.Confirm && pulseTwice(view)) return
        val constant = when (kind) {
            Haptic.Tick -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Light -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /**
     * Two short clicks through the vibrator, as primitives where the motor has them and a [18, 24, 18] ms waveform
     * otherwise. False when this phone can't, so the caller falls back to CONFIRM. Needs the VIBRATE permission.
     */
    private fun pulseTwice(view: View): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val context = view.context
        // The person's touch-feedback switch: off plays no cue at all, as performHapticFeedback would.
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return true
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return false
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return false
        val effect = if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, .8f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, .8f, 42)
                .compose()
        } else VibrationEffect.createWaveform(longArrayOf(0, 18, 24, 18), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        return true
    }
}

/**
 * Keeps a confirm or reject whole: a tick or light tick within [HOLD_MS] after one would cancel it on the motor, as
 * when a hold menu opens under a finger that is still moving onto a row. No Android types, so it runs in unit tests.
 */
class HapticGate {
    private var lastCue = Long.MIN_VALUE / 2

    fun allow(kind: Haptic, nowMs: Long): Boolean = when (kind) {
        Haptic.Confirm, Haptic.Reject -> { lastCue = nowMs; true }
        Haptic.Tick, Haptic.Light -> nowMs - lastCue >= HOLD_MS
    }

    companion object {
        const val HOLD_MS = 90L
    }
}
