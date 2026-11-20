package cz.teous.liquid

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * A piece of glass that leaves a capsule the way a drop leaves a tap, as `Liquid.Drop` in liquid.js: it bulges out,
 * stretches the bridge until the bridge snaps, wobbles, then blooms into its final shape (a menu, results, a toast).
 * Closing runs it backwards and merges it into the capsule. [home] false shrinks it away where it is instead.
 */
class Drop(val radius: Float = 15f, val breakAt: Float = 12f, val home: Boolean = true) {
    var open = false
        private set
    private var attached = true
    private var span = 0f
    private val events = mutableListOf<DropEvent>()

    val t = Spring(0f, .36f, .8f)   // travel out of the source
    val g = Spring(0f, .42f, .74f)  // bloom into the final shape
    val w = Spring(0f, .3f, .3f)    // wobble after the bridge snaps
    val k = Spring(if (home) 1f else 0f, .3f, .9f) // size of the drop itself
    val a = Spring(0f, .45f, 1f)    // without a home: 0 blooms from the edge facing the source, 1 shrinks about the centre
    private val springs = listOf(t, g, w, k, a)

    /** A drop that has fully gone starts over from its source; one on its way or shrinking carries on from where it is. */
    fun show() {
        if (!visible) {
            for (s in listOf(t, g, w, a)) s.snap(0f)
            k.snap(1f)
            attached = true
        }
        open = true
    }

    fun hide() {
        open = false
        // Moving a homeless drop's anchor to its centre is invisible only while it is fully bloomed.
        if (!home && abs((1 - g.x) * (1 - a.x) * span) < 1) a.snap(1f)
    }

    val visible get() = open || g.x > .002f || (if (home) t.x > .002f else k.x > .01f)
    val resting get() = springs.all { it.resting }

    fun step(dt: Float, reduced: Boolean = false) {
        if (open) {
            t.t = 1f; g.t = if (t.x > .5f) 1f else 0f; k.t = 1f; a.t = 0f
        } else {
            g.t = 0f
            if (home) t.t = if (g.x < .3f) 0f else 1f
            else { k.t = if (g.x < .25f) 0f else 1f; a.t = 1f }
        }
        for (s in springs) s.step(dt, reduced)
    }

    /** [source]: the circle in the capsule the drop leaves from; [final]: the rect it blooms into. */
    fun geometry(source: Circle, final: GlassRect): DropGeometry {
        val r = radius * k.x
        val fcx = final.x + final.w / 2
        val fcy = final.y + final.h / 2
        val alongY = alongY(source, final, fcx, fcy)
        val fwd = if (alongY) sign(fcy - source.y).let { if (it == 0f) 1f else it } else sign(fcx - source.x).let { if (it == 0f) 1f else it }
        // Where the drop waits before it blooms: just inside the final shape, on the side facing the source, on the
        // final shape's own centre line. A menu pushed along to stay on screen is reached on a slant, and blooms
        // evenly about its centre, so its rows are revealed from the middle out, never from one side.
        var end = if (alongY) Point(fcx, if (fwd > 0) final.y + r else final.bottom - r)
        else Point(if (fwd > 0) final.x + r else final.right - r, fcy)
        if (!home) {
            span = hypot(fcx - end.x, fcy - end.y)
            end = Point(lerp(end.x, fcx, a.x), lerp(end.y, fcy, a.x))
        }
        val c = Point(lerp(source.x, end.x, t.x), lerp(source.y, end.y, t.x))
        var m = lerpRect(GlassRect(c.x - r, c.y - r, 2 * r, 2 * r, r), final, g.x)
        // Wobble: stretch along the way it travelled, squeeze across, keeping its area.
        val wk = .05f * w.x
        if (abs(wk) > 1e-4f) {
            val sx = if (alongY) 1 - wk else 1 + wk
            val sy = if (alongY) 1 + wk else 1 - wk
            val cx = m.x + m.w / 2
            val cy = m.y + m.h / 2
            m = GlassRect(cx - m.w * sx / 2, cy - m.h * sy / 2, m.w * sx, m.h * sy, m.r * min(sx, sy))
        }
        val r2 = min(r + (m.r - r) * g.x, min(m.w / 2, m.h / 2))
        val c2 = if (alongY) Point(source.x.coerceIn(m.x + r2, max(m.x + r2, m.right - r2)), if (fwd > 0) m.y + r2 else m.bottom - r2)
        else Point(if (fwd > 0) m.x + r2 else m.right - r2, source.y.coerceIn(m.y + r2, max(m.y + r2, m.bottom - r2)))
        // The gap between the two surfaces. Straight along the axis it is the gap along the axis, as it always was;
        // on a slant it is measured along the line between the circles' centres, so the bridge thins and snaps
        // by its real length.
        val gapAxis = if (alongY) (if (fwd > 0) m.y - (source.y + source.r) else source.y - source.r - m.bottom)
        else (if (fwd > 0) m.x - (source.x + source.r) else source.x - source.r - m.right)
        val across = if (alongY) c2.x - source.x else c2.y - source.y
        val gap = if (across == 0f) gapAxis else hypot(across, gapAxis + source.r + r2) - source.r - r2
        val restAxis = if (alongY) (if (fwd > 0) final.y - (source.y + source.r) else source.y - source.r - final.bottom)
        else (if (fwd > 0) final.x - (source.x + source.r) else source.x - source.r - final.right)
        // At rest the bridge's far end sits under the source wherever the final shape reaches, so the rest gap is
        // measured from there, and the bridge is sure to have snapped (breakAt is at most .8 of it) by the time it lands.
        val rf = final.radius
        val restAcross = if (alongY) source.x.coerceIn(final.x + rf, max(final.x + rf, final.right - rf)) - source.x
        else source.y.coerceIn(final.y + rf, max(final.y + rf, final.bottom - rf)) - source.y
        val restGap = if (restAcross == 0f) restAxis else hypot(restAcross, restAxis + source.r + rf) - source.r - rf
        val sp = if (home || open) spread(gap, min(breakAt, restGap * .8f)) else 0f
        val nowAttached = sp > 0
        // The bridge snapping sets the drop wobbling and the source recoiling; meeting again makes the source bulge.
        if (nowAttached != attached) {
            if (!nowAttached) { w.v += 6; events += DropEvent.Snap } else events += DropEvent.Merge
            attached = nowAttached
        }
        fun ins(v: Float) = v.coerceIn(0f, 9999f)
        return DropGeometry(
            rect = m,
            bridge = Bridge(Point(source.x, source.y), source.r, c2, r2, sp),
            clip = ClipInset(ins(m.y - final.y), ins(final.right - m.right), ins(final.bottom - m.bottom), ins(m.x - final.x), m.r),
            alpha = smooth(.55f, .95f, g.x),
        )
    }

    /** Events since the last call: [DropEvent.Snap] when the bridge broke, [DropEvent.Merge] when it touched its source again. */
    fun take(): List<DropEvent> = events.toList().also { events.clear() }
}

data class Circle(val x: Float, val y: Float, val r: Float)

enum class DropEvent { Snap, Merge }

/** The content's clip inside the drop's final rect, as CSS `inset(top right bottom left round radius)`. */
data class ClipInset(val top: Float, val right: Float, val bottom: Float, val left: Float, val radius: Float)

data class DropGeometry(val rect: GlassRect, val bridge: Bridge, val clip: ClipInset, val alpha: Float)

/**
 * liquid.js `dropAxis()`: a drop travels up or down when its final shape lies wholly above or below the source,
 * sideways when wholly beside it, otherwise toward the nearer centre. A menu shifted along to stay on screen still
 * rises out of a control at the screen's edge; measured sideways it would overlap its source, and its bridge would
 * never snap.
 */
private fun alongY(source: Circle, final: GlassRect, fcx: Float, fcy: Float): Boolean = when {
    final.y >= source.y + source.r || final.bottom <= source.y - source.r -> true
    final.x >= source.x + source.r || final.right <= source.x - source.r -> false
    else -> abs(fcy - source.y) >= abs(fcx - source.x)
}
