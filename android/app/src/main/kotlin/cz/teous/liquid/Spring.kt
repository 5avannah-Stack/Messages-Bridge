package cz.teous.liquid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A damped spring with SwiftUI's parameters: [response] in seconds and the [damping] fraction, as `Liquid.Spring` in
 * liquid.js. Its velocity carries over when the target changes, so motion never restarts from rest. Under Reduce
 * Motion it lands at once.
 */
class Spring(x: Float = 0f, var response: Float = .22f, var damping: Float = .85f) {
    var x = x
    var v = 0f
    var t = x

    fun tune(response: Float, damping: Float): Spring {
        this.response = response
        this.damping = damping
        return this
    }

    /** Integrates in steps of at most 4 ms, so a long frame can't make it overshoot more than a short one. */
    fun step(dt: Float, reduced: Boolean = false) {
        if (reduced) {
            snap(t)
            return
        }
        val w = 2 * PI.toFloat() / response
        val n = max(1, ceil(dt / .004f).toInt())
        val h = dt / n
        repeat(n) {
            v += (-w * w * (x - t) - 2 * damping * w * v) * h
            x += v * h
        }
        // Lands exactly on the target, or a value left at 39.99999 against 40 flips a pixel and redraws the glass forever.
        if (resting) snap(t)
    }

    fun snap(to: Float) {
        x = to
        t = to
        v = 0f
    }

    val resting: Boolean get() = abs(x - t) < 1e-3f && abs(v) < 1e-2f
}
