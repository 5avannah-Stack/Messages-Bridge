package cz.teous.liquid

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** JavaScript's `Math.max(a, Math.min(b, x))`: unlike coerceIn it accepts a range that came out empty and gives [lo]. */
fun clamp(x: Float, lo: Float, hi: Float) = max(lo, min(hi, x))

/**
 * The dock lens as two edges in cell units (dock-lab.html `physics()`, motion "stretch"): the leading edge runs ahead
 * and the trailing one catches up, so the lens stretches on the way and lands with one soft overshoot. Both edges keep
 * their speed through every retarget.
 */
class DockLens(cell: Float = 0f) {
    val left = Spring(cell)
    val right = Spring(cell)
    val resting get() = left.resting && right.resting
    val centre get() = (left.x + right.x) / 2

    fun snap(cell: Float) { left.snap(cell); right.snap(cell) }

    /** [target] is the cell (fractional while a finger drags it); under Reduce Motion it lands on [selected]. */
    fun step(dt: Float, target: Float, selected: Int, pressed: Boolean, reduced: Boolean) {
        val d = target - centre
        val dir = if (abs(d) > .02f) sign(d) else 0f
        left.t = target
        right.t = target
        val trail = if (pressed) TRAIL_PRESSED else TRAIL
        when {
            dir > 0 -> { right.tune(LEAD.first, LEAD.second); left.tune(trail.first, trail.second) }
            dir < 0 -> { left.tune(LEAD.first, LEAD.second); right.tune(trail.first, trail.second) }
            else -> { left.tune(CALM.first, CALM.second); right.tune(CALM.first, CALM.second) }
        }
        if (reduced) snap(selected.toFloat()) else { left.step(dt); right.step(dt) }
        // The edges may cross a little when the lens turns around, never further.
        if (right.x - left.x < -.18f) {
            val mid = (left.x + right.x) / 2
            left.x = mid + .09f
            right.x = mid - .09f
        }
    }

    /** How much of cell [i] the lens covers, 0 to 1: icons brighten and grow with it. */
    fun near(i: Int) = c01(1 - max(0f, max(left.x - i, i - right.x)))

    /** The lens thins as it stretches, by up to 12 %. */
    val thin get() = min(.12f, max(0f, right.x - left.x) * .18f)

    companion object {
        val LEAD = .15f to .86f
        val TRAIL = .32f to .72f
        val TRAIL_PRESSED = .28f to .8f
        val CALM = .22f to .85f
    }
}

/** Where a lens is drawn: a pill scaled about its centre. */
data class LensDraw(val x: Float, val y: Float, val w: Float, val h: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)

/**
 * The lens that marks the current item in a row or column (a menu, a toolbar, a switch), as `Liquid.Lens` in
 * liquid.js without mouse following. Its two edges are springs along [vertical] or horizontal; it stays hidden until
 * [step] gets `ready`, then fades and grows in on its item.
 */
class RowLens(private val vertical: Boolean = true) {
    private val a = Spring(0f)
    private val b = Spring(0f)
    private val vis = Spring(0f, .2f, 1f)
    private var fresh = true
    private var cross = 0f to 0f
    val resting get() = a.resting && b.resting && vis.resting

    /** Starts the next appearance on its item instead of sliding in from the last one. */
    fun reset() { fresh = true }

    /**
     * [target]: the item's span along the axis (start, end) and across it (offset, size), relative to the box the
     * lens sits in, or null for none. Returns what to draw, or null while it is hidden.
     */
    fun step(dt: Float, target: ItemSpan?, ready: Boolean, reduced: Boolean): LensDraw? {
        vis.t = if (target != null && ready) 1f else 0f
        vis.step(dt, reduced)
        if (target != null) {
            a.t = target.start; b.t = target.end; cross = target.crossStart to target.crossSize
            if (fresh) { a.snap(target.start); b.snap(target.end); fresh = false }
        } else if (vis.x < .01f) {
            fresh = true
            return null
        }
        val dir = sign((a.t + b.t - a.x - b.x) / 2)
        when {
            dir > 0 -> { b.tune(.15f, .86f); a.tune(.3f, .74f) }
            dir < 0 -> { a.tune(.15f, .86f); b.tune(.3f, .74f) }
            else -> { a.tune(.22f, .85f); b.tune(.22f, .85f) }
        }
        a.step(dt, reduced); b.step(dt, reduced)
        val len = max(8f, b.x - a.x)
        val rest = max(1f, b.t - a.t)
        val thin = min(.12f, max(0f, len / rest - 1) * .18f)
        val v = vis.x
        val grow = .9f + .1f * v
        return if (vertical) LensDraw(cross.first, a.x, cross.second, len, grow * (1 - thin), grow, v)
        else LensDraw(a.x, cross.first, len, cross.second, grow, grow * (1 - thin), v)
    }
}

data class ItemSpan(val start: Float, val end: Float, val crossStart: Float, val crossSize: Float)
