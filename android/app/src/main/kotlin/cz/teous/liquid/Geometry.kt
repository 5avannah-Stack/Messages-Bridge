package cz.teous.liquid

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/* Shapes and the bridges between them, in dp (the web's CSS pixels), with no Android types so they run in unit tests. */

fun c01(x: Float) = x.coerceIn(0f, 1f)
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = c01((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)
}

/** A rounded rect: one piece of a glass outline. */
data class GlassRect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) {
    val right get() = x + w
    val bottom get() = y + h
    /** The corner radius the outline really uses. */
    val radius get() = max(0f, min(r, min(w / 2, h / 2)))
}

fun lerpRect(a: GlassRect, b: GlassRect, t: Float) =
    GlassRect(lerp(a.x, b.x, t), lerp(a.y, b.y, t), max(2f, lerp(a.w, b.w, t)), max(2f, lerp(a.h, b.h, t)), lerp(a.r, b.r, t))

data class Point(val x: Float, val y: Float)

/** The liquid bridge between two circles (a metaball). [spread] 0 thins it to nothing. */
data class Bridge(val c1: Point, val r1: Float, val c2: Point, val r2: Float, val spread: Float)

/** A bridge's outline: two cubic curves joined by straight lines, p1 → p3 → p4 → p2. */
data class BridgeCurve(
    val p1: Point, val h1: Point, val h3: Point, val p3: Point,
    val p4: Point, val h4: Point, val h2: Point, val p2: Point,
)

/** The bridge stays full while the pieces overlap and thins away as the gap between them opens to [breakAt]. */
fun spread(gap: Float, breakAt: Float): Float =
    if (gap <= 0) .6f else .6f * c01(1 - gap / breakAt).toDouble().pow(1.4).toFloat()

/** A neck at this spread is about a third of its ends' radius thick; a thinner one would be a hairline. */
const val NECK = .17f

/**
 * How two ends of radius [r] that lie in a row join into one body, as their bridge's spread. Apart, the neck thins as
 * [spread] does, but it is never a hairline: below [NECK] it isn't there, so it forms and snaps a few dp thick.
 * Overlapping, it fills from .6 to 1 as the ends sink a radius into each other. At 1 the bridge is the hull of the two
 * ends, so by then the pieces have no notch between them ([oneCapsule]).
 */
fun join(gap: Float, breakAt: Float, r: Float): Float {
    if (gap <= 0) return lerp(.6f, 1f, c01(-gap / r))
    val s = spread(gap, breakAt)
    return if (s < NECK) 0f else s
}

/**
 * True when two pieces of one row lie at least a radius deep in each other. By [join] their neck is full by then, so
 * together they are exactly one capsule.
 */
fun oneCapsule(a: GlassRect, b: GlassRect): Boolean =
    abs(a.y - b.y) < 1e-3f && abs(a.h - b.h) < 1e-3f && abs(a.radius - b.radius) < 1e-3f &&
        min(a.right, b.right) - max(a.x, b.x) >= a.radius

/**
 * The pieces as the glass gets them: every run of pieces that are [oneCapsule] with each other becomes that capsule.
 * Pieces lying along each other's edges are not the same glass as one capsule: the bend takes a shared edge for two
 * edges and bends the page at each piece's end inside the other, which shows as seams across the body.
 */
fun joined(pieces: List<GlassRect>): List<GlassRect> {
    val out = ArrayList<GlassRect>(pieces.size)
    for (p in pieces) {
        var body = p
        // A piece may join bodies that were apart until now, so it takes in every body it reaches.
        while (true) {
            val i = out.indexOfFirst { oneCapsule(it, body) }
            if (i < 0) break
            val o = out.removeAt(i)
            val x = min(o.x, body.x)
            body = GlassRect(x, o.y, max(o.right, body.right) - x, o.h, o.r)
        }
        out += body
    }
    return out
}

/**
 * liquid.js `bridge()`: the outline is returned in one winding, so it unions with the rounded rects without holes.
 * Null when there's nothing to draw.
 */
fun bridgeCurve(b: Bridge): BridgeCurve? {
    val (c1, r1, c2, r2, sp) = b
    val d = hypot(c2.x - c1.x, c2.y - c1.y)
    if (sp <= .002f || d <= abs(r1 - r2) || d > r1 + r2 * 2.5f) return null
    fun ac(x: Float) = acos(x.coerceIn(-1f, 1f))
    val u1 = if (d < r1 + r2) ac((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)) else 0f
    val u2 = if (d < r1 + r2) ac((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)) else 0f
    val ab = atan2(c2.y - c1.y, c2.x - c1.x)
    val ms = ac((r1 - r2) / d)
    val pi = PI.toFloat()
    val a1 = ab + u1 + (ms - u1) * sp
    val a2 = ab - u1 - (ms - u1) * sp
    val a3 = ab + pi - u2 - (pi - u2 - ms) * sp
    val a4 = ab - pi + u2 + (pi - u2 - ms) * sp
    fun at(c: Point, a: Float, r: Float) = Point(c.x + r * cos(a), c.y + r * sin(a))
    val p1 = at(c1, a1, r1)
    val p2 = at(c1, a2, r1)
    val p3 = at(c2, a3, r2)
    val p4 = at(c2, a4, r2)
    val k = min(sp * 2.4f, hypot(p1.x - p3.x, p1.y - p3.y) / (r1 + r2)) * min(1f, d * 2 / (r1 + r2))
    fun h(p: Point, a: Float, r: Float) = Point(p.x + r * cos(a), p.y + r * sin(a))
    val h1 = h(p1, a1 - pi / 2, r1 * k)
    val h2 = h(p2, a2 + pi / 2, r1 * k)
    val h3 = h(p3, a3 + pi / 2, r2 * k)
    val h4 = h(p4, a4 - pi / 2, r2 * k)
    val quad = listOf(p1, p3, p4, p2)
    var area = 0f
    for (i in quad.indices) {
        val p = quad[i]
        val q = quad[(i + 1) % 4]
        area += p.x * q.y - q.x * p.y
    }
    return if (area > 0) BridgeCurve(p1, h1, h3, p3, p4, h4, h2, p2)
    else BridgeCurve(p2, h2, h4, p4, p3, h3, h1, p1)
}

/**
 * The boxes the rim's light is laid over: one for each piece, except that pieces of one height that overlap side by
 * side share one. A piece's light runs down its own height, so such pieces would lay the same light over their overlap
 * once each, and the rim would glow there. Pieces of different heights, such as a drop over its capsule, keep their own.
 * A neck between two ends of one row is lit with them: its gap joins their boxes, or the rim would dim across it.
 */
fun lightBoxes(shapes: List<GlassRect>, bridges: List<Bridge> = emptyList()): List<Box> {
    val boxes = ArrayList<Box>(shapes.size)
    /** Adds [s], taking in every box of its row that it reaches. A neck that reaches none lights nothing. */
    fun add(s: Box, neck: Boolean) {
        var box = s
        var met = false
        // A piece may join boxes that were apart until now, so it takes in every box it reaches.
        while (true) {
            val i = boxes.indexOfFirst { abs(it.y - box.y) < 1e-3f && abs(it.h - box.h) < 1e-3f && it.x <= box.x + box.w && box.x <= it.x + it.w }
            if (i < 0) break
            met = true
            val o = boxes.removeAt(i)
            val x = min(o.x, box.x)
            box = Box(x, o.y, max(o.x + o.w, box.x + box.w) - x, o.h)
        }
        if (met || !neck) boxes += box
    }
    for (s in shapes) add(Box(s.x, s.y, s.w, s.h), false)
    for (b in bridges) {
        val gap = abs(b.c2.x - b.c1.x) - b.r1 - b.r2
        if (gap <= 0 || abs(b.c1.y - b.c2.y) > 1e-3f || abs(b.r1 - b.r2) > 1e-3f || bridgeCurve(b) == null) continue
        // A hair wider than the gap, so rounding never leaves it short of the ends it joins.
        add(Box(min(b.c1.x, b.c2.x) + b.r1 - .01f, b.c1.y - b.r1, gap + .02f, 2 * b.r1), true)
    }
    return boxes
}

/**
 * Each light box reaches past its piece's ends by up to [pad], so the rim's softened edge at a piece's far left and right
 * is lit like the rest; cut at the piece's own box, it showed as a short upright line beside each end. Boxes of one row
 * reach only halfway to each other, so a gap between them is never lit twice. The same as liquid.js's padBoxes.
 */
fun padBoxes(boxes: List<Box>, pad: Float): List<Box> = boxes.map { b ->
    var left = pad
    var right = pad
    for (o in boxes) {
        if (o === b || abs(o.y - b.y) >= 1e-3f || abs(o.h - b.h) >= 1e-3f) continue
        if (o.x + o.w <= b.x) left = min(left, (b.x - o.x - o.w) / 2)
        if (o.x >= b.x + b.w) right = min(right, (o.x - b.x - b.w) / 2)
    }
    Box(b.x - left, b.y, b.w + left + right, b.h)
}

/** Everything one piece of glass is drawn from in a frame. */
data class GlassOutline(val shapes: List<GlassRect>, val bridges: List<Bridge> = emptyList()) {
    val visible get() = shapes.isNotEmpty()

    companion object {
        val Empty = GlassOutline(emptyList())
    }
}
