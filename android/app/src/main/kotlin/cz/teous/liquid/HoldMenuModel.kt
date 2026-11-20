package cz.teous.liquid

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A hold menu's rows this frame: where they rest, their clip while the drop grows, their alpha, the lens and the lit row. */
data class HoldMenuDraw(val final: GlassRect, val clip: ClipInset, val alpha: Float, val lens: LensDraw?, val row: Int?)

/**
 * A hold menu's drop, rows and lens: the one code path for the dock's cells ([DockModel]) and for any other control
 * ([HoldMenuModel]), so every hold menu leaves its control and moves the same way (teodor-taste, Hold menus).
 */
internal class MenuDrop {
    private val drop = Drop(radius = 18f, breakAt = 12f)
    private val lens = RowLens(vertical = true)
    private var size = MenuSize(0, 0f)
    private var rows = 0

    /** The lit row while the menu is open. */
    var row: Int? = null
    /** The menu's resting rect while its drop shows. Rows are hit-tested here, never on the drop's animation. */
    var final: GlassRect? = null
        private set
    val open get() = drop.open
    val resting get() = drop.resting && lens.resting

    fun show(size: MenuSize, row: Int?) {
        this.size = size
        rows = size.rows
        this.row = row
        lens.reset()
        drop.show()
    }

    /** Closes the menu; its drop merges back into the control over the next frames. */
    fun hide() {
        rows = 0
        row = null
        drop.hide()
    }

    fun step(dt: Float, reduced: Boolean) = drop.step(dt, reduced)

    /** Gone at once with no merge, for a control that is gone itself. */
    fun vanish() {
        hide()
        // Two settled steps: the bloom goes first, then the drop's travel, which waits on it.
        repeat(2) { drop.step(0f, reduced = true) }
        drop.take()
        lens.step(0f, null, false, reduced = true)
        final = null
    }

    /** The row under ([x], [y]) in dp, with 4 dp to spare above and below and 8 dp to the sides. */
    fun rowAt(x: Float, y: Float): Int? {
        val f = final ?: return null
        return (0 until rows).firstOrNull {
            val top = f.y + DockModel.MENU_PAD + it * (DockModel.ROW + DockModel.ROW_GAP)
            y >= top - 4 && y <= top + DockModel.ROW + 4 && x >= f.x - 8 && x <= f.x + f.w + 8
        }
    }

    /** Lights the row under a sliding finger. True when that changed the lit row, which ticks. */
    fun slide(x: Float, y: Float): Boolean {
        val r = rowAt(x, y) ?: return false
        if (r == row) return false
        row = r
        return true
    }

    /**
     * Lays the menu out for this frame: centred above [source] (the circle inside the control the drop leaves from),
     * 10 dp above [top] (the control's top edge), kept 18 dp inside a screen [width] dp wide. With [below] (the
     * control's bottom edge) it opens downward instead, 10 dp under it, as out of a top bar. The drop's snap kicks
     * [recoil] in and its merge out. Returns the drop's piece and bridge and the rows' look, or null while no drop shows.
     */
    fun frame(dt: Float, source: Circle, top: Float, width: Float, reduced: Boolean, recoil: Spring, below: Float? = null): Pair<DropGeometry, HoldMenuDraw>? {
        if (!drop.visible) {
            final = null
            lens.step(dt, null, false, reduced)
            return null
        }
        val w = max(size.contentWidth + 2 * DockModel.MENU_PAD, MIN_WIDTH)
        val h = 2 * DockModel.MENU_PAD + size.rows * DockModel.ROW + max(0, size.rows - 1) * DockModel.ROW_GAP
        val f = GlassRect(clamp(source.x - w / 2, EDGE, width - EDGE - w), below?.let { it + 10 } ?: (top - 10 - h), w, h, 22f)
        final = f
        val geo = drop.geometry(source, f)
        for (e in drop.take()) recoil.v += if (e == DropEvent.Snap) -6f else 4f
        // The current row lights up once the menu has nearly bloomed, not while it is still a blur leaving the control.
        val r = row
        val span = r?.let { val y = DockModel.MENU_PAD + it * (DockModel.ROW + DockModel.ROW_GAP); ItemSpan(y, y + DockModel.ROW, DockModel.MENU_PAD, w - 2 * DockModel.MENU_PAD) }
        val lensDraw = lens.step(dt, span, drop.open && drop.g.x > .8f, reduced)
        return geo to HoldMenuDraw(f, geo.clip, geo.alpha, lensDraw, r)
    }

    companion object {
        const val MIN_WIDTH = 180f
        const val EDGE = 18f
    }
}

sealed interface HoldMenuEvent {
    data class Feedback(val kind: Haptic) : HoldMenuEvent
    /** A press on the control lifted before it became a hold: the control's own action. */
    data object Tap : HoldMenuEvent
    /** A menu opened, by a hold when [held], otherwise by [HoldMenuModel.open]. */
    data class Opened(val held: Boolean) : HoldMenuEvent
    data class Picked(val row: Int) : HoldMenuEvent
}

/** What the app shows this frame. [source] is the control's glass in dp from the window's top left, null while it is hidden. */
data class HoldMenuInputs(val width: Float, val source: GlassRect?, val reduced: Boolean)

/** Everything a control and its hold menu draw in one frame, in dp. Equal frames draw nothing new. */
data class HoldMenuFrame(
    /** The control and its drop, one body. */
    val glass: GlassOutline,
    /** The control's glass, recoil included, or null while it is hidden. */
    val source: GlassRect?,
    val menu: HoldMenuDraw?,
)

/**
 * A hold menu out of any control (a button beside the dock, a key, a face), with no Android types so it runs in unit
 * tests. It is the dock's hold menu ([MenuDrop]) with a control for a source: the same drop, numbers, recoil, lens and
 * haptics. Holding the control for [DockModel.HOLD_MS] without moving opens it; slide to a row and lift to pick it,
 * lift anywhere else to close it. A shorter press is a [HoldMenuEvent.Tap]. [open] opens it without a hold, such as on
 * a tap: then a tap on a row picks it and a tap anywhere else closes it. Input is in dp from the window's top left.
 */
class HoldMenuModel {
    private val menu = MenuDrop()
    private val recoil = Spring(0f, .3f, .55f)
    private val events = mutableListOf<HoldMenuEvent>()
    private var inputs = HoldMenuInputs(390f, null, false)
    private var clock = 0f

    private enum class Touch { None, Control, Rows }
    private var touch = Touch.None
    private var startX = 0f
    private var startY = 0f
    private var since = 0f
    private var moved = false
    /** This press has had its chance at a hold: the menu opened, or there was none to open. A slow tap is still a tap. */
    private var holdDone = false
    /** The menu was opened by the finger that is still down. */
    var held = false
        private set
    /** The row a menu opened without a hold had lit, back when a press on its rows lifts off them. */
    private var openRow: Int? = null

    val menuOpen get() = menu.open
    /** The menu's resting rect while its drop shows. */
    val menuRect get() = menu.final
    val pressed get() = touch != Touch.None

    fun take(): List<HoldMenuEvent> = events.toList().also { events.clear() }

    /** Where a finger takes the control this frame. */
    fun hitBox(): Box? = inputs.source?.let { hitBox(it) }

    /** The menu row at ([x], [y]), as a lift there would pick it. */
    fun rowAt(x: Float, y: Float): Int? = menu.rowAt(x, y)

    /**
     * A finger went down. Returns false when the menu doesn't take it. While a menu opened without a hold shows, it
     * takes every touch: on the menu to pick a row, anywhere else to close it.
     */
    fun press(x: Float, y: Float): Boolean {
        if (touch != Touch.None) return false
        val f = menu.final
        if (menu.open && !held) {
            if (f != null && x >= f.x - 8 && x <= f.right + 8 && y >= f.y - 4 && y <= f.bottom + 4) {
                touch = Touch.Rows
                if (menu.slide(x, y)) events += HoldMenuEvent.Feedback(Haptic.Tick)
            } else close()
            return true
        }
        if (hitBox()?.contains(x, y) != true) return false
        touch = Touch.Control
        startX = x; startY = y; since = clock
        moved = false; holdDone = false; held = false
        return true
    }

    fun move(x: Float, y: Float) {
        when (touch) {
            Touch.Control -> if (held) { if (menu.slide(x, y)) events += HoldMenuEvent.Feedback(Haptic.Tick) }
                else if (hypot(x - startX, y - startY) > 8) moved = true
            Touch.Rows -> if (menu.slide(x, y)) events += HoldMenuEvent.Feedback(Haptic.Tick)
            Touch.None -> {}
        }
    }

    fun release(x: Float, y: Float, cancelled: Boolean = false) {
        val t = touch
        touch = Touch.None
        when (t) {
            Touch.Control -> if (held) {
                held = false
                // Lifting on a row picks it. Lifting anywhere else, the control included, closes the menu and changes
                // nothing, even though the current row is lit (teodor-taste, Hold menus).
                val row = if (cancelled) null else menu.rowAt(x, y)
                menu.hide()
                if (row != null) pick(row)
            } else if (!cancelled && hitBox()?.contains(x, y) == true) events += HoldMenuEvent.Tap
            Touch.Rows -> {
                val row = if (cancelled) null else menu.rowAt(x, y)
                if (row != null) { menu.hide(); pick(row) } else menu.row = openRow
            }
            Touch.None -> {}
        }
    }

    /** Opens a menu of [size] without a hold, such as on a tap, with [row] lit. False when one is already open. */
    fun open(size: MenuSize, row: Int?): Boolean {
        if (menu.open || size.rows <= 0 || inputs.source == null) return false
        held = false
        openRow = row
        menu.show(size, row)
        events += HoldMenuEvent.Feedback(Haptic.Confirm)
        events += HoldMenuEvent.Opened(held = false)
        return true
    }

    /** Picks [row] of the open menu, as TalkBack does. */
    fun choose(row: Int) {
        if (!menu.open) return
        menu.hide()
        held = false
        pick(row)
    }

    /** Closes the menu and picks nothing, as Android's Back does. Returns whether it closed one. */
    fun close(): Boolean {
        if (!menu.open) return false
        menu.hide()
        return true
    }

    private fun pick(row: Int) {
        events += HoldMenuEvent.Feedback(Haptic.Confirm)
        events += HoldMenuEvent.Picked(row)
    }

    /** Nothing moves and nothing waits: the frame loop can sleep, and the glass isn't redrawn. */
    val idle get() = touch == Touch.None && recoil.resting && menu.resting

    /**
     * Advances [dtMs] (capped at 50 ms, as the dock) and returns the frame. [holdMenu] gives the hold menu's size when a
     * hold opens it, or null when the control has none; [holdRow] its starting row.
     */
    fun frame(dtMs: Float, inputs: HoldMenuInputs, holdMenu: () -> MenuSize?, holdRow: () -> Int?): HoldMenuFrame {
        this.inputs = inputs
        val ms = min(50f, max(0f, dtMs))
        clock += ms
        val dt = ms / 1000
        // A control that goes takes its menu and the finger on it with it.
        val s = inputs.source
        if (s == null) {
            menu.vanish()
            recoil.snap(0f)
            touch = Touch.None
            held = false
            return HoldMenuFrame(GlassOutline.Empty, null, null)
        }
        recoil.step(dt, inputs.reduced)
        menu.step(dt, inputs.reduced)
        // A hold opens the menu, unless the finger moved first.
        if (touch == Touch.Control && !holdDone && !moved && clock - since >= DockModel.HOLD_MS) {
            holdDone = true
            val size = holdMenu()
            if (size != null && size.rows > 0 && !menu.open) {
                held = true
                menu.show(size, holdRow())
                events += HoldMenuEvent.Feedback(Haptic.Confirm)
                events += HoldMenuEvent.Opened(held = true)
            }
        }
        // The control recoils a little when the drop snaps off it and bulges when the drop merges back, as the dock.
        val rc = recoil.x * 2.5f
        val body = GlassRect(s.x - rc, s.y - rc, s.w + 2 * rc, s.h + 2 * rc, s.r + rc)
        val shapes = arrayListOf(body)
        val bridges = ArrayList<Bridge>(1)
        val m = menu.frame(dt, Circle(s.x + s.w / 2, s.y + s.h / 2, min(s.w, s.h) / 2 - 4), s.y, inputs.width, inputs.reduced, recoil)
        if (m != null) {
            shapes += m.first.rect
            bridges += m.first.bridge
        }
        return HoldMenuFrame(GlassOutline(shapes, bridges), body, m?.second)
    }

    companion object {
        /** The control plus 4 dp around it, where a finger takes it. */
        fun hitBox(source: GlassRect) = Box(source.x - 4, source.y - 4, source.w + 8, source.h + 8)
    }
}
