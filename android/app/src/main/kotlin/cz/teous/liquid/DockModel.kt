package cz.teous.liquid

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The pulses the dock asks for (dock-lab.html `PULSE`); [Haptics] maps them to the phone's own feedback. */
enum class Haptic { Tick, Light, Confirm, Reject }

sealed interface DockEvent {
    data class Feedback(val kind: Haptic) : DockEvent
    /** The page to show changed: the selected cell, or the page under Search while Search is selected. */
    data class Page(val page: Int) : DockEvent
    data class SearchOpened(val keyboard: Boolean) : DockEvent
    data object SearchClosed : DockEvent
    data class MenuPicked(val cell: Int, val row: Int) : DockEvent
    /** The finger lifted on an action cell, or it was tapped from the accessibility tree. */
    data class Action(val cell: Int) : DockEvent
}

/**
 * What the side bubble shows, held for as long as its glass is out. An app drops its bubble the moment the state
 * clears, but the glass still has to merge back into the dock, so the bubble's icon and progress come from here and
 * never straight from the app: they can't leave before their glass, and a new bubble shows at once.
 */
class BubbleContent<T : Any> {
    var shown: T? = null
        private set

    fun update(current: T?, frame: DockFrame): T? {
        shown = current ?: shown.takeIf { frame.bubble != null }
        return shown
    }
}

/**
 * What a dock cell scrubs through, such as Gallery's Photos through its years. Holding the cell still for [DockModel.HOLD_MS]
 * stretches the dock into a timeline. [open] and [move] get the finger's place along the stretched dock's track, 0 at
 * its left end and 1 at its right, also while it is still stretching and past the ends. [open] says whether there is
 * anything to scrub; [move] whether the finger crossed a mark worth a tick, such as a new year. [close] runs when the
 * finger lifts or the touch is cancelled.
 */
interface Scrubber {
    fun open(at: Float): Boolean
    fun move(at: Float): Boolean
    fun close()
}

/**
 * The stretched dock's timeline this frame: its track from [left] to [right] at [y], measured from the screen's edges so
 * its marks hold still while the glass stretches around them, clipped to the stretched [bar] and faded by [alpha].
 */
data class ScrubDraw(val left: Float, val right: Float, val y: Float, val bar: Box, val alpha: Float)

/** A hold menu's size: its row count and the widest row's content, in dp. */
data class MenuSize(val rows: Int, val contentWidth: Float)

/** What the app shows around the dock this frame. [bubble] is whether its state is set, whatever the page. */
data class DockInputs(
    val width: Float, val height: Float, val safeBottom: Float, val ime: Float, val reduced: Boolean,
    val bubble: Boolean = false,
    /** A toast's size in dp while one shows: it rises out of the dock as a drop. */
    val toast: Size2? = null,
    /** Search's field has focus. A keyboard hidden while it does closes search (Search, the keyboard going away). */
    val fieldFocused: Boolean = false,
)

/** A width and height in dp. */
data class Size2(val w: Float, val h: Float)

/** A toast this frame: its resting rect, its clip while it grows and its content's alpha. */
data class ToastDraw(val final: GlassRect, val clip: ClipInset, val alpha: Float)

data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

data class IconDraw(val x: Float, val y: Float, val scale: Float, val near: Float, val alpha: Float)
data class BubbleDraw(val rect: Box, val contentAlpha: Float)
data class MenuDraw(val cell: Int, val final: GlassRect, val clip: ClipInset, val alpha: Float, val lens: LensDraw?, val row: Int?)
data class FieldDraw(
    val field: Box, val textX: Float, val textW: Float, val textAlpha: Float,
    val closeX: Float, val closeY: Float, val closeAlpha: Float, val closeShown: Boolean,
)

/** Everything the dock draws in one frame, in dp from the screen's top left. Equal frames draw nothing new. */
data class DockFrame(
    val glass: GlassOutline,
    val lens: LensDraw,
    val icons: List<IconDraw>,
    val bubble: BubbleDraw?,
    val menu: MenuDraw?,
    val field: FieldDraw,
    val toast: ToastDraw? = null,
    val dim: Float,
    val resultsAlpha: Float,
    val resultsBottom: Float,
    val hit: Box,
    val searchOpen: Boolean,
    val dock: Box,
    val scrub: ScrubDraw? = null,
)

/**
 * The phone dock of dock-lab.html with its default options (motion Stretch, Lens highlight, Clear glass, Liquid side bubble,
 * Search Grow): its touch state machine, `physics()` and `render()`, in dp and milliseconds, without
 * Android types. [scrubber] gives the cells that scrub ([Scrubber]), and [hasMenu] says which cells open a hold menu; a
 * cell that scrubs opens no menu. [isAction] says which cells act rather than show a page, such as New: pressing one
 * or sliding onto it only ticks and selects nothing, and lifting on it acts ([DockEvent.Action]), as Clinch's Create. The
 * action waits for the lens to reach the cell, at most [ACT_WAIT_MS], so a tap from across the dock never has the action
 * take the dock away while the lens is still on its way.
 */
class DockModel(
    val count: Int, val searchCell: Int?, selected: Int = 0,
    private val scrubber: (Int) -> Scrubber? = { null },
    private val isAction: (Int) -> Boolean = { false },
    private val hasMenu: (Int) -> Boolean = { false },
) {
    var selected = selected
        private set
    var page = selected
        private set
    var searchOpen = false
        private set
    var menuOpen: Int? = null
        private set
    /** A held cell has stretched the dock into its timeline ([Scrubber]). */
    var scrubbing = false
        private set
    val pressed get() = nav.pressed

    private var inputs = DockInputs(390f, 800f, 0f, 0f, false)
    private val events = mutableListOf<DockEvent>()
    private var clock = 0f

    private class Nav {
        var pressed = false; var pos = 0f; var startCell = 0; var startX = 0f; var x = 0f; var moved = false
        var restCell = -1; var restSince = 0f
        /** The cell the finger is over, page or action, so each new one ticks once. */
        var crossed = -1
        /** This press's hold has fired: it opened a scrub or was turned down, and won't try again until the next press. */
        var held = false
    }
    private val nav = Nav()
    /** The action cell a finger lifted on, while the lens finishes its way there; [actingSince] is when the finger lifted. */
    private var acting: Int? = null
    private var actingSince = 0f

    /** The hold menu, shared with every other control's ([HoldMenuModel]); [menuCell] is the cell it leaves. */
    private val menu = MenuDrop()
    private var menuCell = 0
    private val toastDrop = Drop(radius = 18f, breakAt = 12f)
    private var scrub: Scrubber? = null

    private val lens = DockLens(selected.toFloat())
    private val dockCx = Spring(195f, .42f, .82f)
    private val bx = Spring(0f, .42f, .74f)
    private val bw = Spring(50f, .36f, .8f)
    private val bs = Spring(0f, .32f, .66f)
    private val p = Spring(0f, .5f, .82f)
    private val wob = Spring(0f, .3f, .3f)
    private val recoil = Spring(0f, .3f, .55f)
    /** The dock stretching into a scrub's timeline: Grow's spring, without the field. */
    private val stretch = Spring(0f, .5f, .82f)
    private val groupSprings = listOf(dockCx, bx, bw, bs, p, wob, recoil, stretch)
    /** The keyboard has been up while search is open, so its going away can close search. */
    private var imeSeen = false
    private var badgeAlive = false
    private var badgeAttached = true
    private var placed = false

    fun take(): List<DockEvent> = events.toList().also { events.clear() }

    /* ---------- Geometry ---------- */
    private val dockW get() = min(count * CELL + 2 * PAD, inputs.width - 36)
    private val cellW get() = (dockW - 2 * PAD) / count
    private fun fieldBottom() = min(inputs.height - inputs.safeBottom - 24, inputs.height - inputs.ime - 8)
    fun live(): Box {
        val w = dockW
        return Box(dockCx.x - w / 2, fieldBottom() - H, w, H)
    }
    private fun posFromX(x: Float): Float {
        val r = live()
        return clamp((x - r.x - PAD) / max(1f, r.w - 2 * PAD) * count - .5f, 0f, (count - 1).toFloat())
    }
    /** The finger's place along a scrub's track, unclamped: the track runs [TRACK_INSET] in from the stretched dock's ends. */
    private fun along(x: Float): Float { val edge = EDGE + TRACK_INSET; return (x - edge) / max(1f, inputs.width - 2 * edge) }
    /** The bar plus 3 dp above and 16 dp below, since thumbs land low. */
    fun hitBox(): Box { val l = live(); return Box(l.x, l.y - 3, l.w, H + 19) }

    /* ---------- Input, in dp from the screen's top left ---------- */
    private fun select(i: Int, feedback: Boolean = true) {
        if (selected == i) return
        selected = i
        if (feedback) events += DockEvent.Feedback(Haptic.Tick)
        if (i != searchCell) page = i
        events += DockEvent.Page(page)
    }

    /** A finger went down. Returns false when the dock doesn't take it. */
    fun press(x: Float, y: Float): Boolean {
        if (searchOpen || !hitBox().contains(x, y)) return false
        acting = null
        nav.pressed = true
        nav.pos = posFromX(x); nav.startX = x; nav.x = x; nav.moved = false; nav.held = false
        nav.startCell = nav.pos.roundToInt()
        nav.restCell = nav.startCell; nav.restSince = clock
        nav.crossed = nav.startCell
        // An action cell ticks as it's pressed, as Clinch's Create (it's never the current page), and acts on the lift.
        if (isAction(nav.startCell)) { events += DockEvent.Feedback(Haptic.Tick); return true }
        if (nav.startCell == selected) events += DockEvent.Feedback(Haptic.Light)
        select(nav.startCell)
        return true
    }

    /** The finger slid onto [cell]: a page is selected at once; an action cell only ticks, and acts on the lift. */
    private fun reach(cell: Int) {
        if (cell == nav.crossed) return
        nav.crossed = cell
        if (isAction(cell) || cell == selected) events += DockEvent.Feedback(Haptic.Tick) else select(cell)
    }

    fun move(x: Float, y: Float) {
        if (!nav.pressed) return
        nav.x = x
        scrub?.let { if (it.move(along(x))) events += DockEvent.Feedback(Haptic.Tick); return }
        val r = live()
        if (abs(x - nav.startX) > 8) nav.moved = true
        menuOpen?.let { cell ->
            if (y < r.y - 4) {
                if (menu.slide(x, y)) events += DockEvent.Feedback(Haptic.Tick)
                return
            }
            if (posFromX(x).roundToInt() == cell) return
            closeMenu()
        }
        nav.pos = posFromX(x)
        val cell = nav.pos.roundToInt()
        if (cell != nav.restCell) { nav.restCell = cell; nav.restSince = clock }
        reach(cell)
    }

    fun release(x: Float, y: Float, cancelled: Boolean = false) {
        if (!nav.pressed) return
        nav.pressed = false
        // Nothing plays as a scrub lets go (navigation-interaction: release a drag, none).
        if (scrubbing) { endScrub(); return }
        if (cancelled) { closeMenu(); return }
        val overCell = posFromX(x).roundToInt()
        val overDock = y >= live().y - 4
        menuOpen?.let { cell ->
            // Lifting on a row picks it. Lifting anywhere else, the held cell included, closes the menu and changes
            // nothing, even though the current row is lit (teodor-taste, Hold menus).
            val row = menu.rowAt(x, y)
            closeMenu()
            if (row != null) { events += DockEvent.Feedback(Haptic.Confirm); events += DockEvent.MenuPicked(cell, row) }
            else if (overDock && overCell == searchCell) openSearch(keyboard = true)
            return
        }
        // Search focuses only once the finger lifts on its cell, so a drag across it never raises the keyboard. An action
        // cell acts the same way, whether the press began on it or slid onto it.
        if (selected == searchCell && overCell == searchCell) openSearch(keyboard = true)
        else if (overDock && isAction(overCell)) {
            // A slide that crossed Search on its way leaves the page it showed, not Search, selected.
            if (selected == searchCell) select(page, feedback = false)
            acting = overCell
            actingSince = clock
            act()
        }
    }

    /** A tap from the accessibility tree or a keyboard: the same as touching and lifting on the cell. */
    fun activate(cell: Int) {
        if (cell == searchCell) openSearch(keyboard = true) else if (isAction(cell)) events += DockEvent.Action(cell) else select(cell)
    }

    fun openSearch(keyboard: Boolean = true) {
        if (searchOpen || searchCell == null) return
        closeMenu()
        select(searchCell, feedback = false)
        searchOpen = true
        imeSeen = false
        p.tune(.5f, .82f); p.t = 1f
        events += DockEvent.SearchOpened(keyboard)
    }

    fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        p.t = 0f
        select(page, feedback = false)
        events += DockEvent.SearchClosed
    }

    /** Android's Back: closes the menu, then search. Returns whether it closed anything. */
    fun back(): Boolean {
        val had = menuOpen != null || searchOpen
        closeMenu(); closeSearch()
        return had
    }

    /** Selects a cell from the app, without feedback, such as after restoring state. */
    fun show(cell: Int) = select(cell, feedback = false)

    /** Puts the lens on the selected cell at once, for a dock shown again: after an action took the dock away, the lens
     * would otherwise slide back from the action cell it was lifted on. */
    fun restLens() { acting = null; lens.snap(selected.toFloat()) }

    /** Runs the lifted-on action once the lens is on its cell, or once [ACT_WAIT_MS] have passed. The lens then heads back
     * to the page, unseen when the action took the dock away. */
    private fun act() {
        val cell = acting ?: return
        val there = abs(lens.left.x - cell) <= ARRIVED && abs(lens.right.x - cell) <= ARRIVED
        if (!there && clock - actingSince < ACT_WAIT_MS) return
        acting = null
        events += DockEvent.Action(cell)
    }

    private fun openMenu(cell: Int, size: MenuSize, row: Int?) {
        menuOpen = cell
        menuCell = cell
        menu.show(size, row)
        events += DockEvent.Feedback(Haptic.Confirm)
    }

    private fun closeMenu() {
        menuOpen = null
        menu.hide()
    }

    private fun endScrub() {
        scrub?.close()
        scrub = null
        scrubbing = false
        stretch.t = 0f
    }

    /* ---------- Frame ---------- */

    /** Nothing moves and nothing waits: the frame loop can sleep, and the glass isn't redrawn. */
    val idle: Boolean
        get() = !nav.pressed && acting == null && lens.resting && groupSprings.all { it.resting } && menu.resting && toastDrop.resting

    /**
     * Advances [dtMs] (capped at 50 ms, as the lab does) and returns the frame. [menuSizeFor] gives a hold menu's
     * size when a hold opens one; [menuRow] its starting row.
     */
    fun frame(dtMs: Float, inputs: DockInputs, menuSizeFor: (Int) -> MenuSize, menuRow: (Int) -> Int?): DockFrame {
        val first = !placed || this.inputs.width != inputs.width
        this.inputs = inputs
        if (first) { dockCx.snap(inputs.width / 2); placed = true }
        val ms = min(50f, max(0f, dtMs))
        clock += ms
        // The keyboard going away closes search, as Back does: the system's Back or the keyboard's own hide key take it
        // down without reaching the app. Only a keyboard that was up and has gone all the way counts, with the field
        // still focused; an app that keeps its results up with the keyboard down clears the field's focus instead, and
        // search opened without the keyboard (a tag from a viewer) stays open.
        if (searchOpen) {
            if (inputs.ime > IME_SHOWN) imeSeen = true
            else if (imeSeen && inputs.ime <= 0f && inputs.fieldFocused) closeSearch()
            if (!inputs.fieldFocused) imeSeen = false
        }
        physics(ms / 1000, menuSizeFor, menuRow)
        return render(ms / 1000)
    }

    private fun physics(dt: Float, menuSizeFor: (Int) -> MenuSize, menuRow: (Int) -> Int?) {
        val rm = inputs.reduced
        // A held finger drags the lens, until a menu opens: then it glides onto the icon the menu belongs to.
        val target = menuOpen?.toFloat() ?: if (nav.pressed) nav.pos else (acting ?: selected).toFloat()
        lens.step(dt, target, acting ?: selected, nav.pressed, rm)
        act()

        // Group: the dock and its side bubble centre together, frozen while a finger is down so nothing slides under it.
        val dw = dockW
        val shown = inputs.bubble && !searchOpen
        if (!nav.pressed) {
            if (shown) {
                if (!badgeAlive) {
                    badgeAlive = true
                    bx.snap(dockCx.x - dw / 2 + 25); bs.snap(.55f); bw.snap(50f)
                }
                val bwT = 50f
                val left = max(18f, (inputs.width - bwT - GAP - dw) / 2)
                bw.t = bwT; bx.t = left + bwT / 2; bs.t = 1f; dockCx.t = left + bwT + GAP + dw / 2
            } else {
                dockCx.t = inputs.width / 2
                if (badgeAlive) {
                    bw.t = 50f; bs.t = .5f; bx.t = dockCx.x - dw / 2 + 25
                    if (bs.x < .6f && abs(bx.x - bx.t) < 4) badgeAlive = false
                }
            }
        }
        for (s in groupSprings) s.step(dt, rm)
        menu.step(dt, rm)
        // A toast waits while a hold menu or search has the dock, and comes back once they're gone.
        if (inputs.toast != null && menuOpen == null && !searchOpen && !scrubbing) toastDrop.show() else toastDrop.hide()
        toastDrop.step(dt, rm)

        // A hold on a cell that scrubs stretches the dock into its timeline, and on a cell with a menu opens it, unless
        // the finger moved off the cell first. A scrub with nothing to show turns the hold down once per press.
        if (nav.pressed && menuOpen == null && !searchOpen && !scrubbing && !nav.held) {
            val cell = nav.startCell
            if (nav.restCell == cell && !nav.moved && clock - nav.restSince >= HOLD_MS) {
                val s = scrubber(cell)
                if (s != null) {
                    nav.held = true
                    if (s.open(along(nav.x))) {
                        scrub = s; scrubbing = true; stretch.t = 1f
                        events += DockEvent.Feedback(Haptic.Confirm)
                    } else events += DockEvent.Feedback(Haptic.Reject)
                } else if (hasMenu(cell)) openMenu(cell, menuSizeFor(cell), menuRow(cell))
            }
        }
    }

    private fun render(dt: Float): DockFrame {
        val vw = inputs.width
        val live = live()
        val cw = cellW
        val px = p.x
        val pc = c01(px)
        val sx = stretch.x
        val cy = live.y + H / 2
        val shapes = ArrayList<GlassRect>(4)
        val bridges = ArrayList<Bridge>(2)

        // Grow: the dock stretches into the search field, or into a scrub's timeline. Only one of them is ever under way.
        val grow = px + sx
        val dock = Box(lerp(live.x, EDGE, grow), live.y, max(50f, lerp(live.w, vw - 2 * EDGE, grow)), H)
        val field = dock
        // The dock recoils a little when the side bubble or a menu snaps off it.
        val rc = recoil.x * 2.5f
        shapes += GlassRect(dock.x - rc, dock.y - rc, dock.w + 2 * rc, dock.h + 2 * rc, 25 + rc)

        var bRect: Box? = null
        if (badgeAlive) {
            // Wobble after the bridge snaps: stretched along the way it left, squeezed across, keeping its area.
            val k = .06f * wob.x
            val h0 = H * bs.x
            val w0 = bw.x - 50 + h0
            val w = w0 * (1 + k)
            val h = h0 * (1 - k)
            val b = Box(bx.x - w / 2, cy - h / 2, w, h)
            bRect = b
            shapes += GlassRect(b.x, b.y, b.w, b.h, h / 2)
            val gap = dock.x - (b.x + b.w)
            val sp = spread(gap, GAP * .8f)
            bridges += Bridge(Point(dock.x + 25, cy), 25f, Point(b.x + b.w - h / 2, cy), h / 2, sp)
            val attached = sp > 0
            if (attached != badgeAttached) {
                if (!attached) { wob.v -= 6; recoil.v -= 6 } else recoil.v += 4
                badgeAttached = attached
            }
        } else badgeAttached = true

        // Hold menu: a drop out of the held cell, part of the dock's glass until its bridge snaps.
        val menuDraw = menu.frame(dt, Circle(live.x + PAD + (menuCell + .5f) * cw, cy, 21f), live.y, vw, inputs.reduced, recoil)?.let { (geo, look) ->
            shapes += geo.rect
            bridges += geo.bridge
            MenuDraw(menuCell, look.final, look.clip, look.alpha, look.lens, look.row)
        }

        // Lens
        val l = lens.left.x
        val r = lens.right.x
        var lx = live.x + PAD + l * cw
        var lw = (r - l + 1) * cw
        if (px > 0) { lx = lerp(lx, field.x + 4, px); lw = max(20f, lerp(lw, field.w - 8, px)) }
        val scaleY = if (px < .01f) 1 - lens.thin else 1f
        // The lens dissolves as the dock starts to grow, so the field never shows a second outline inside its own.
        // Closing brings it back earlier than opening takes it away, so the dock is never an empty capsule.
        val (la, lb) = if (searchOpen || scrubbing) 0f to .2f else .05f to .35f
        val lensDraw = LensDraw(lx, live.y + 4, lw, H - 8, 1f, scaleY, (1 - smooth(la, lb, px)) * (1 - smooth(la, lb, sx)))

        // Icons: colour and size follow how close the lens is, so the highlight moves continuously.
        var searchIconX = 0f
        val icons = List(count) { i ->
            var x = live.x + PAD + (i + .5f) * cw
            var alpha = 1f
            var near = lens.near(i)
            var s = 1 + .08f * near + MAGNIFY * near
            if (px > 0) {
                if (i == searchCell) {
                    // Closing, the magnifier heads home ahead of the shrinking field, so it never crosses the others.
                    x = lerp(x, field.x + 26, if (searchOpen) smooth(.12f, 1f, px) else smooth(.42f, 1f, px))
                    near = max(near, pc); s = lerp(s, 1f, pc)
                } else {
                    // They melt away over the first stretch of the morph: fading, and shrinking only a little.
                    val (ia, ib) = if (searchOpen) 0f to .3f else .12f to .5f
                    alpha *= 1 - smooth(ia, ib, px); s *= 1 - .18f * smooth(ia, ib, px)
                }
            }
            if (sx > 0) {
                // Every icon melts away as the dock stretches into the timeline, as the others do for search.
                val (ia, ib) = if (scrubbing) 0f to .3f else .12f to .5f
                alpha *= 1 - smooth(ia, ib, sx); s *= 1 - .18f * smooth(ia, ib, sx)
            }
            if (i == searchCell) searchIconX = x
            IconDraw(x, cy, s, near, alpha)
        }

        // The side bubble's content shows only once it has left the dock, so it never sits on the dock's own icons.
        val bubble = bRect?.takeIf { bs.x > .58f }?.let { b ->
            BubbleDraw(b, smooth(-26f, 2f, dock.x - (b.x + b.w)))
        }

        // Search chrome
        val fieldTop = fieldBottom() - H
        val endGap = 46f
        val tx = max(field.x + 48, searchIconX + 22)
        val fieldDraw = FieldDraw(
            field, tx, max(0f, field.x + field.w - endGap - tx), smooth(.5f, .85f, px),
            field.x + field.w - 45, field.y + 5, smooth(.6f, .9f, px), searchOpen,
        )

        // Toast: a drop out of the dock's middle, the topmost glass at the bottom of the screen (README, Drops).
        var toastDraw: ToastDraw? = null
        val ts = inputs.toast
        if (toastDrop.visible && ts != null) {
            val cxt = live.x + live.w / 2
            val final = GlassRect(clamp(cxt - ts.w / 2, 18f, vw - 18 - ts.w), live.y - 10 - ts.h, ts.w, ts.h, min(22f, ts.h / 2))
            val geo = toastDrop.geometry(Circle(cxt, cy, 21f), final)
            shapes += geo.rect
            bridges += geo.bridge
            for (e in toastDrop.take()) recoil.v += if (e == DropEvent.Snap) -6f else 4f
            toastDraw = ToastDraw(final, geo.clip, geo.alpha)
        }

        // The timeline shows as the dock stretches and leaves as it shrinks back.
        val scrubDraw = if (sx > .001f) ScrubDraw(EDGE + TRACK_INSET, vw - EDGE - TRACK_INSET, cy, dock, smooth(.15f, .6f, sx)) else null

        return DockFrame(
            toast = toastDraw, scrub = scrubDraw,
            glass = GlassOutline(shapes, bridges), lens = lensDraw, icons = icons, bubble = bubble,
            menu = menuDraw, field = fieldDraw, dim = .82f * pc, resultsAlpha = c01((px - .35f) / .5f),
            resultsBottom = inputs.height - fieldTop + 10, hit = hitBox(), searchOpen = searchOpen, dock = dock,
        )
    }

    companion object {
        const val CELL = 50f
        const val H = 50f
        const val PAD = 6f
        const val GAP = 8f
        const val HOLD_MS = 360f
        /** The longest a lifted-on action waits for the lens, and how near the cell both its edges must be, in cells, for
         * the lens to count as there: a lens still stretched from the cell it left hasn't arrived. */
        const val ACT_WAIT_MS = 220f
        const val ARRIVED = .12f
        /** A keyboard this tall, in dp, is up; suggestion strips and floating keyboards stay under it. */
        const val IME_SHOWN = 50f
        /** The grown dock's gap to the screen's edges, for search and a scrub alike. */
        const val EDGE = 18f
        /** A scrub's track runs this far in from the stretched dock's ends. */
        const val TRACK_INSET = 20f
        const val MAGNIFY = .2f
        const val ROW = 44f
        const val ROW_GAP = 2f
        const val MENU_PAD = 6f
    }
}
