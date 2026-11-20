package cz.teous.liquid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/* ---------- Composer A: the dock splits into +, the field and send (dock-lab.html, Split; README, Picked: composer A) ---------- */

/**
 * What the composer is laid out from in a frame, in dp. [fieldH] is the field's height for its text, [sendW] the send
 * piece's width, [period] one frame of the display in ms, which caps the close's first frames.
 */
data class ComposerInputs(
    val width: Float, val height: Float, val safeBottom: Float, val ime: Float, val reduced: Boolean,
    val fieldH: Float = ComposerModel.H, val sendW: Float = ComposerModel.H, val menuRows: Int = 0, val menuW: Float = 0f,
    val period: Float = 1000f / 60,
    /** A middle sized to its contents, such as a split bar's cells: the three pieces then rest as one centred group. */
    val middleW: Float? = null,
)

/** What the composer draws in a frame, in dp from the window's top left. */
data class ComposerFrame(
    val glass: GlassOutline, val plus: Box, val field: Box, val send: Box,
    /** The field where it rests once split: its text is laid out at this width, so it never wraps as the field narrows. */
    val fieldRest: Box,
    /** The pieces' icons and text: they show once the pieces have parted. */
    val contentAlpha: Float,
    /** The dock's own icons and lens, still on the bar: 1 while the pieces are the dock, gone once they part. */
    val dockAlpha: Float,
    /**
     * The bar the dock's icons and lens are drawn in, at [dockAlpha]: the dock's own bar while the composer splits, and
     * while it closes the pieces' span as it is now, so they are squeezed into the shrinking glass and land with it.
     */
    val dockBar: Box,
    /** 0 is a +, 1 has turned it into a ×, while its menu is open. */
    val plusTurn: Float,
    val menu: DropGeometry?, val menuFinal: GlassRect?,
)

/**
 * Composer A, with no Android types so it runs in unit tests. It starts as the dock's bar [from] and splits into three
 * pieces joined by bridges that thin and break as they part, on Split's spring. [open] false runs it back into [from].
 * The field grows upwards with its text while the circles stay on the bottom line, and the whole row rides the keyboard.
 * The + menu is a drop out of the + circle, as the dock's hold menu is.
 */
class ComposerModel(var from: Box?) {
    var open = true
        set(value) {
            if (value != field) sinceTurn = 0f
            field = value
        }
    private val p = Spring(if (from == null) 1f else 0f, OPEN_RESPONSE, OPEN_DAMPING)
    // Real time since the last turn (open or close), to find the close's first frames.
    private var sinceTurn = 0f
    private val turn = Spring(0f, .3f, .8f)
    val menu = Drop(radius = 18f, breakAt = 12f)
    private val springs get() = listOf(p, turn)
    // How far the pieces run ahead of the spring while it closes, so that they are on the bar at the handover.
    private var lead = 0f
    private val leadTarget get() = if (open) 0f else HANDOVER * (1 - c01(p.x))

    val idle get() = springs.all { it.resting } && menu.resting && lead == leadTarget
    /**
     * True once a closing composer is back inside the dock's bar, so the dock can take over. It hands over as the pieces
     * meet the bar, not when the spring comes to rest, so the bar never sits there empty.
     */
    val closed get() = !open && p.x <= HANDOVER

    /** [dt] in ms since the last frame. */
    fun frame(dt: Float, i: ComposerInputs): ComposerFrame {
        p.t = if (open) 1f else 0f
        // Splitting may overshoot and settle, like Split. Closing is a touch underdamped, so it lands firmly instead of
        // creeping in; the pieces are clamped at the bar (t below), and the dock takes over before the spring could
        // swing past it, so nothing ever runs beyond the dock's own edges.
        if (open) p.tune(OPEN_RESPONSE, OPEN_DAMPING) else p.tune(CLOSE_RESPONSE, CLOSE_DAMPING)
        turn.t = if (menu.open) 1f else 0f
        // A frame longer than 50 ms (the app was in the background) moves no further than one of 50 ms, as in the dock.
        val raw = min(50f, max(0f, dt))
        // The close's first frames: the app composes the page it goes back to in the frame the close starts in, and
        // that frame runs long. Advanced by its full length, the pieces would jump a fifth of the way in one frame.
        // So for the close's first CLOSE_GRACE ms of real time, a frame advances by one frame of the display at most:
        // the pieces start a few frames late instead of jumping.
        val ms = if (!open && sinceTurn < CLOSE_GRACE) min(raw, i.period) else raw
        sinceTurn += raw
        val sec = ms / 1000
        for (s in springs) s.step(sec, i.reduced)
        menu.step(sec, i.reduced)

        val bottom = min(i.height - i.safeBottom - 24, i.height - i.ime - 8)
        val plus1: Box
        val send1: Box
        val field1: Box
        // A middle sized to its cells, never wider than the room between the screen's edges: its cells narrow instead.
        val mw = i.middleW?.let { min(it, i.width - 2 * EDGE - H - i.sendW - 2 * GAP) }
        if (mw == null) {
            // The composer: the circles at the screen's edges, and the field as wide as the room between them.
            plus1 = Box(EDGE, bottom - H, H, H)
            send1 = Box(i.width - EDGE - i.sendW, bottom - H, i.sendW, H)
            field1 = Box(plus1.x + H + GAP, bottom - i.fieldH, send1.x - GAP - (plus1.x + H + GAP), i.fieldH)
        } else {
            // A split bar: the circles beside a middle as wide as its cells, centred as one group (Gallery's viewer).
            val x0 = max(EDGE, (i.width - (H + GAP + mw + GAP + i.sendW)) / 2)
            plus1 = Box(x0, bottom - H, H, H)
            field1 = Box(x0 + H + GAP, bottom - H, mw, H)
            send1 = Box(field1.x + mw + GAP, bottom - H, i.sendW, H)
        }
        // The three start as the whole bar, overlapping so their union has no notch, as Split's pieces do.
        val f = from ?: Box(plus1.x, plus1.y, send1.x + send1.w - plus1.x, H)
        val third = f.w / 3
        // The dock takes over at HANDOVER, with the spring still that far from the bar: 1.6 dp a side on a phone,
        // which the bar would snap in one frame. So the closing pieces lead the spring by a share that grows to
        // HANDOVER as they close, and are on the bar when it hands over. The lead only ever changes gradually, so
        // closing from anywhere, or splitting again on the way back, never jumps.
        val reach = if (i.reduced) 1f else LEAD_SPEED * sec
        lead = if (abs(leadTarget - lead) <= reach) leadTarget else lead + reach * sign(leadTarget - lead)
        val t = max(0f, p.x - lead)
        fun mix(a: Box, b: Box) = Box(lerp(a.x, b.x, t), lerp(a.y, b.y, t), max(40f, lerp(a.w, b.w, t)), max(40f, lerp(a.h, b.h, t)))
        val plus = mix(Box(f.x, f.y, 2 * third, f.h), plus1)
        val field = mix(Box(f.x + third, f.y, third, f.h), field1)
        val send = mix(Box(f.x + third, f.y, 2 * third, f.h), send1)

        // The pieces are one liquid body wherever they touch. A neck joins two ends from a few dp thick and fills as
        // they sink into each other (join); once they lie a radius deep it is full, and the pieces are one capsule,
        // which is what the glass gets (joined). So the bar is the dock's own bar, pixel for pixel, at the start and
        // at the handover, and on the way no piece's end ever shows inside another.
        val pieces = listOf(plus.rect(), send.rect(), field.rect())
        val shapes = joined(pieces).toMutableList()
        val cy = plus.y + plus.h / 2
        // Pieces that are one capsule need no bridge. A field taller than the bar keeps its own piece, and its bridge
        // stays full while it lies deep in the circles.
        fun neck(left: GlassRect, right: GlassRect) = Bridge(
            Point(left.right - R, cy), R, Point(right.x + R, cy), R, if (oneCapsule(left, right)) 0f else join(right.x - left.right, BREAK, R),
        )
        val bridges = mutableListOf(neck(pieces[0], pieces[2]), neck(pieces[2], pieces[1]))

        var drop: DropGeometry? = null
        var final: GlassRect? = null
        if (menu.visible && i.menuRows > 0) {
            val h = MENU_PAD * 2 + i.menuRows * ROW + (i.menuRows - 1) * ROW_GAP
            // Above the + circle, shifted only as far as it takes to stay on screen.
            val w = i.menuW
            val x = (plus1.x + H / 2 - w / 2).coerceIn(EDGE, max(EDGE, i.width - EDGE - w))
            final = GlassRect(x, plus.y - 10 - h, w, h, 22f)
            drop = menu.geometry(Circle(plus.x + plus.w / 2, cy, R), final)
            shapes += drop.rect
            bridges += drop.bridge
        }
        // The bar's icons give way to the pieces' icons one after the other, never on top of each other, so nothing
        // ghosts; the moment between them falls while the pieces move fastest, so the glass never sits there empty.
        // Closing has its own order: the pieces' icons are gone (CLOSE_OUT) before the pieces join into one capsule at
        // about .45, so the join happens on an empty bar, and the dock's icons and lens come in over the whole last
        // stretch (CLOSE_IN to the bar), evenly to the eye: the alpha is raised to 2.2, since a half-alpha white icon
        // on dark glass already looks two-thirds bright. So they reach full strength exactly as the glass lands, and
        // the four events (icons out, join, icons in, landing) are spaced out instead of piling up in the last frames.
        // The dock's icons ride the shrinking bar meanwhile (dockBar), so nothing appears in a bar that is still moving
        // around it. Splitting keeps the dock's own bar and its own curves. A composer split again on its way back
        // takes the split's curves from where it is, so its icons may step rather than fade in that frame.
        val contentAlpha = if (open) smooth(.3f, .55f, t) else smooth(CLOSE_OUT_TO, CLOSE_OUT_FROM, t)
        val dockAlpha = if (open) 1 - smooth(.05f, .28f, t) else (1 - c01(t / CLOSE_IN_FROM)).toDouble().pow(2.2).toFloat()
        val left = min(plus.x, min(field.x, send.x))
        val dockBar = if (open) f else Box(left, f.y, max(plus.x + plus.w, max(field.x + field.w, send.x + send.w)) - left, f.h)
        return ComposerFrame(GlassOutline(shapes, bridges), plus, field, send, field1, contentAlpha, dockAlpha, dockBar, turn.x, drop, final)
    }

    private fun Box.rect() = GlassRect(x, y, w, h, R)

    companion object {
        const val OPEN_RESPONSE = .54f
        const val OPEN_DAMPING = .74f
        // Closing peaks at about the split's speed, a little gentler, so the pieces never snap into the bar, and lands
        // in about a third of a second. A touch underdamped: critically damped, the bar's sides crept in for the last
        // 150 ms, and the dock's icons arrived in a bar that was still moving.
        const val CLOSE_RESPONSE = .46f
        const val CLOSE_DAMPING = .86f
        /** The close's first frames, in ms of real time, each advancing by one frame of the display at most. */
        const val CLOSE_GRACE = 100f
        /** On the close, the pieces' icons fade out from this far out to this near the bar (t), and the dock's fade in from here to the bar. */
        const val CLOSE_OUT_FROM = .70f
        const val CLOSE_OUT_TO = .48f
        const val CLOSE_IN_FROM = .40f
        const val HANDOVER = .02f
        /** The most the closing pieces' lead changes in a second, a few times faster than closing ever asks of it. */
        const val LEAD_SPEED = .5f
        const val H = 50f
        const val R = 25f
        const val EDGE = 18f
        const val GAP = 10f
        const val BREAK = 9f
        const val ROW = 44f
        const val ROW_GAP = 2f
        const val MENU_PAD = 6f
    }
}

/** A circle or cell of the composer or a split bar: its icon, its accessible name and what a tap does. [red] marks a destructive one, whose icon is red. */
class ComposerButton(val label: String, val icon: ImageVector, val red: Boolean = false, val onClick: () -> Unit)

/** One composer for a conversation's life. [from] is the dock's bar it splits out of ([DockState.bar]), null to start split. */
@Stable
class ComposerState internal constructor(from: Box?) {
    internal val model = ComposerModel(from)
    internal var frame by mutableStateOf<ComposerFrame?>(null)
    internal var wake by mutableIntStateOf(0)
    var menuOpen by mutableStateOf(false)
        internal set
    /** How far the pieces reach up from the window's bottom, in dp, so a list can scroll clear of them. */
    var clearance by mutableFloatStateOf(0f)
        internal set

    /** Runs the pieces back into the dock's bar; [LiquidComposer]'s onClosed follows once they're there. */
    fun close() { model.open = false; model.menu.hide(); menuOpen = false; wake++ }
    /** Splits the pieces again from wherever they are, for a conversation opened while they still run back. */
    fun open() { if (!model.open) { model.open = true; wake++ } }
    fun closeMenu() { model.menu.hide(); menuOpen = false; wake++ }
    internal fun toggleMenu() { if (model.menu.open) model.menu.hide() else model.menu.show(); menuOpen = model.menu.open; wake++ }
}

@Composable
fun rememberComposerState(from: Box?): ComposerState = remember { ComposerState(from) }

/**
 * Composer A in liquid glass. Like [LiquidDock] it fills the window above a [backdropSource] and takes touches only on
 * its pieces. [menu] makes + open a drop of rows (photos, camera, file); without it + runs its own action. [sendTag],
 * such as "MMS ×2", widens send into a pill that says what sending costs. The field grows to [maxLines], then scrolls.
 * Pass the dock's [dock] items and its [dockSelected] cell: while the bar splits, and again as it closes, it keeps
 * drawing the dock's own icons and lens, so the handover between the two never shows an empty pill.
 */
@Composable
fun LiquidComposer(
    state: ComposerState,
    text: TextFieldState,
    placeholder: String,
    backdrop: Backdrop?,
    plus: ComposerButton,
    send: ComposerButton,
    modifier: Modifier = Modifier,
    menu: DockMenu? = null,
    sendTag: String? = null,
    dock: List<DockItem> = emptyList(),
    dockSelected: Int = 0,
    focus: FocusRequester = remember { FocusRequester() },
    maxLines: Int = 8,
    onClosed: () -> Unit = {},
    glass: GlassResources = rememberGlassResources(),
) = SplitPieces(state, backdrop, plus, send, modifier, menu, sendTag, dock, dockSelected, onClosed, glass, Middle.Field(text, placeholder, focus, maxLines))

/**
 * The dock split into [left], a bar of [cells] and [right], as the composer splits, for actions on what is open, such as
 * a photo in Gallery's viewer. Show it instead of [LiquidDock] while that is open, as Messages shows [LiquidComposer] in a
 * chat; [ComposerState.close] runs it back into the dock's bar, and [onClosed] follows once it is there. The middle is
 * as wide as its cells (50 dp each, as the dock's), and the three rest as one centred group. Each cell and circle is a
 * button TalkBack names by its label; a [ComposerButton.red] one has a red icon.
 */
@Composable
fun LiquidSplitBar(
    state: ComposerState,
    left: ComposerButton,
    cells: List<ComposerButton>,
    right: ComposerButton,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    dock: List<DockItem> = emptyList(),
    dockSelected: Int = 0,
    onClosed: () -> Unit = {},
    glass: GlassResources = rememberGlassResources(),
) = SplitPieces(state, backdrop, left, right, modifier, null, null, dock, dockSelected, onClosed, glass, Middle.Cells(cells))

/** What the middle piece holds: the composer's field, or a split bar's cells. */
private sealed interface Middle {
    class Field(val text: TextFieldState, val placeholder: String, val focus: FocusRequester, val maxLines: Int) : Middle
    class Cells(val cells: List<ComposerButton>) : Middle
}

/** The composer and the split bar: one body, which differs only in its middle. */
@Composable
private fun SplitPieces(
    state: ComposerState, backdrop: Backdrop?, plus: ComposerButton, send: ComposerButton, modifier: Modifier,
    menu: DockMenu?, sendTag: String?, dock: List<DockItem>, dockSelected: Int, onClosed: () -> Unit, glass: GlassResources,
    middle: Middle,
) {
    val tokens = LocalGlassTokens.current
    val view = LocalView.current
    // Not named density: inside the Canvas below, DrawScope's own density (a Float) must stay in view.
    val screen by rememberUpdatedState(LocalDensity.current)
    val reduced by rememberUpdatedState(prefersReducedMotion())
    val nav = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val measurer = rememberTextMeasurer()
    val dockMeasurer = rememberTextMeasurer()
    val latestMenu by rememberUpdatedState(menu)
    val latestOnClosed by rememberUpdatedState(onClosed)
    var size by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var textH by remember { mutableFloatStateOf(0f) }
    val tagStyle = TextStyle(color = tokens.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val tagW = sendTag?.let { measurer.measure(it, tagStyle).size.width / screen.density }
    val menuW = menu?.let { m -> m.options.maxOf { measurer.measure(it.label, MenuText.copy(fontWeight = FontWeight.SemiBold)).size.width } / screen.density + 14 + 20 + 12 + 20 + 2 * ComposerModel.MENU_PAD }

    fun inputs(): ComposerInputs {
        val d = screen.density
        return ComposerInputs(
            width = size.width / d, height = size.height / d, safeBottom = nav.getBottom(screen) / d, ime = ime.getBottom(screen) / d,
            reduced = reduced, fieldH = if (middle is Middle.Field) max(ComposerModel.H, textH + 28) else ComposerModel.H,
            sendW = tagW?.let { 14 + it + 6 + 22 + 12 } ?: ComposerModel.H,
            menuRows = latestMenu?.options?.size ?: 0, menuW = menuW ?: 0f, period = frameMs(view),
            middleW = (middle as? Middle.Cells)?.let { it.cells.size * DockModel.CELL + 2 * DockModel.PAD },
        )
    }
    var closedSent by remember { mutableStateOf(false) }
    fun step(dt: Float) {
        val i = inputs()
        val f = state.model.frame(dt, i)
        state.frame = f
        state.clearance = i.height - min(f.plus.y, f.field.y)
        for (e in state.model.menu.take()) if (e == DropEvent.Snap) Haptics.perform(view, Haptic.Light)
        // onClosed follows each close once; a composer that was split again on the way back closes afresh.
        if (state.model.open) closedSent = false
        else if (state.model.closed && !closedSent) { closedSent = true; latestOnClosed() }
    }

    // The frame loop runs only while something moves; at rest it waits for input, the keyboard or the text to change.
    LaunchedEffect(state) {
        snapshotFlow { size }.first { it.width > 0 }
        var last = 0L
        step(0f)
        while (true) {
            if (state.model.idle) {
                val key = state.wake to inputs()
                snapshotFlow { state.wake to inputs() }.first { it != key }
                last = 0L
            }
            withFrameNanos { t ->
                val dt = if (last == 0L) frameMs(view) else (t - last) / 1e6f
                last = t
                step(dt)
            }
        }
    }

    BackHandler(enabled = state.menuOpen) { state.closeMenu() }

    // The first frame is worked out as soon as the window's size is known, in the same layout pass, so the frame the
    // composer first appears in already draws it: the dock it replaces never leaves an empty frame behind.
    Box(modifier.fillMaxSize().onSizeChanged {
        size = Size(it.width.toFloat(), it.height.toFloat())
        if (state.frame == null) step(0f)
    }.onPlaced { origin = it.positionInRoot() }) {
        Canvas(Modifier.fillMaxSize()) {
            val f = state.frame ?: return@Canvas
            drawLiquidGlass(f.glass, backdrop, glass, tokens, origin)
            // The dock's lens and icons, as the dock draws them, fading as the pieces part and back as they close.
            // They are laid out in the dock's bar and squeezed sideways into the bar as it is now (dockBar), so on the
            // close they ride the shrinking glass and land with it. The lens keeps its own rim: it is the dock's lens.
            val bar = state.model.from
            if (bar != null && dock.isNotEmpty() && f.dockAlpha > .001f) {
                val cw = (bar.w - 2 * DockModel.PAD) / dock.size
                val s = f.dockBar.w / bar.w
                val at = { x: Float -> f.dockBar.x + (x - bar.x) * s }
                val cy = f.dockBar.y + f.dockBar.h / 2
                drawLens(LensDraw(at(bar.x + DockModel.PAD + dockSelected * cw), f.dockBar.y + 4, cw * s, f.dockBar.h - 8, 1f, 1f, f.dockAlpha), tokens, radius = 21f)
                dock.forEachIndexed { i, item ->
                    val near = if (i == dockSelected) 1f else 0f
                    val icon = IconDraw(at(bar.x + DockModel.PAD + (i + .5f) * cw), cy, 1 + .08f * near + DockModel.MAGNIFY * near, near, f.dockAlpha)
                    drawDockIcon(CellLook(item.icon, item.initial, item.picture), icon, tokens, dockMeasurer)
                }
            }
            val a = f.contentAlpha
            if (a <= .001f) return@Canvas
            val pc = Offset((f.plus.x + f.plus.w / 2) * density, (f.plus.y + f.plus.h / 2) * density)
            withTransform({ rotate(45f * f.plusTurn, pc) }) {
                drawLineIcon(plus.icon, f.plus.x + f.plus.w / 2, f.plus.y + f.plus.h / 2, 1f, iconColor(plus, tokens).copy(alpha = a), size = 22f)
            }
            if (middle is Middle.Cells) middle.cells.forEachIndexed { i, c ->
                val cell = cellBox(f, i, middle.cells.size)
                drawLineIcon(c.icon, cell.x + cell.w / 2, cell.y + cell.h / 2, 1f, iconColor(c, tokens).copy(alpha = a), size = 22f)
            }
            if (sendTag != null) {
                val layout = measurer.measure(sendTag, tagStyle)
                val top = (f.send.y + f.send.h / 2) * density - layout.size.height / 2f
                for (s in tokens.textShadow) drawText(layout, color = tokens.text.copy(alpha = a), topLeft = Offset((f.send.x + 14) * density, top), shadow = s.scaled(density))
                drawLineIcon(send.icon, f.send.x + f.send.w - 12 - 11, f.send.y + f.send.h / 2, 1f, iconColor(send, tokens).copy(alpha = a), size = 22f)
            } else {
                drawLineIcon(send.icon, f.send.x + f.send.w / 2, f.send.y + f.send.h / 2, 1f, iconColor(send, tokens).copy(alpha = a), size = 22f)
            }
        }
        when (middle) {
            is Middle.Field -> ComposerField(state, middle.text, middle.placeholder, middle.focus, middle.maxLines, tokens) { textH = it }
            is Middle.Cells -> middle.cells.forEachIndexed { i, c -> Tap(state, { cellBox(it, i, middle.cells.size) }, c.label, c.onClick) }
        }
        if (menu != null) ComposerMenu(state, menu, tokens)
        Tap(state, { it.plus }, plus.label) {
            if (latestMenu != null) { state.toggleMenu(); if (state.menuOpen) Haptics.perform(view, Haptic.Confirm) } else plus.onClick()
        }
        Tap(state, { it.send }, send.label) { send.onClick() }
    }
}

private val MenuText = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

private fun iconColor(b: ComposerButton, tokens: GlassTokens) = if (b.red) tokens.red else tokens.onGlass

/** A split bar's [i]th of [n] cells in the middle piece as it is now, its padding kept, so the cells spread with it. */
private fun cellBox(f: ComposerFrame, i: Int, n: Int): Box {
    val w = (f.field.w - 2 * DockModel.PAD) / n
    return Box(f.field.x + DockModel.PAD + i * w, f.field.y, w, f.field.h)
}

/** A piece's tap target while its content shows. Not composed otherwise, since a clickable at 0 x 0 still takes touches. */
@Composable
private fun Tap(state: ComposerState, piece: (ComposerFrame) -> Box, label: String, onClick: () -> Unit) {
    val shown by remember(state) { derivedStateOf { (state.frame?.contentAlpha ?: 0f) > .5f } }
    if (!shown) return
    Box(
        Modifier
            .placeAt { state.frame?.takeIf { it.contentAlpha > .5f }?.let(piece) }
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
    )
}

/**
 * The field's text: as wide as the field at rest, anchored to its bottom, so the field grows upwards with each line.
 * [onHeight] reports the text's height in dp, which sets the field's.
 */
@Composable
private fun ComposerField(
    state: ComposerState, text: TextFieldState, placeholder: String, focus: FocusRequester, maxLines: Int, tokens: GlassTokens,
    onHeight: (Float) -> Unit,
) {
    val density = LocalDensity.current.density
    val style = TextStyle(color = tokens.text, fontSize = 16.sp, shadow = tokens.textShadow.last().scaled(density))
    Box(
        Modifier
            .layout { m, c ->
                val f = state.frame
                if (f == null) {
                    val p = m.measure(Constraints.fixed(0, 0))
                    return@layout layout(0, 0) { p.place(0, 0) }
                }
                // Laid out at the resting width and clipped to the field as it is now, so the text never re-wraps
                // while the field narrows or widens.
                val w = ((f.fieldRest.w - 32) * density).roundToInt().coerceAtLeast(1)
                val now = ((f.field.w - 32) * density).roundToInt().coerceIn(1, w)
                val p = m.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = c.maxHeight))
                layout(now, p.height) {
                    p.place(((f.field.x + 16) * density).roundToInt(), ((f.field.y + f.field.h - 14) * density).roundToInt() - p.height)
                }
            }
            .graphicsLayer {
                alpha = state.frame?.contentAlpha ?: 0f
                clip = true
            }
            .onSizeChanged { onHeight(it.height / density) },
    ) {
        BasicTextField(
            state = text,
            modifier = Modifier.focusRequester(focus),
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            lineLimits = TextFieldLineLimits.MultiLine(1, maxLines),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorator = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.text.isEmpty()) BasicText(placeholder, style = style.copy(color = tokens.text3))
                    inner()
                }
            },
        )
    }
}

/** The + menu's rows at their final place, clipped to the drop as it grows and faded in as it lands. */
@Composable
private fun ComposerMenu(state: ComposerState, menu: DockMenu, tokens: GlassTokens) {
    val measurer = rememberTextMeasurer()
    Box(
        Modifier
            .placeAt { state.frame?.menuFinal?.let { Box(it.x, it.y, it.w, it.h) } }
            .graphicsLayer {
                val d = state.frame?.menu
                alpha = d?.alpha ?: 0f
                clip = true
                shape = d?.let { ClipShape(it.clip) } ?: RectangleShape
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            menu.options.forEachIndexed { i, o ->
                val cy = ComposerModel.MENU_PAD + i * (ComposerModel.ROW + ComposerModel.ROW_GAP) + ComposerModel.ROW / 2
                drawLineIcon(o.icon, ComposerModel.MENU_PAD + 14 + 10, cy, 1f, tokens.text, size = 20f)
                val layout = measurer.measure(o.label, MenuText.copy(color = tokens.text))
                val topLeft = Offset((ComposerModel.MENU_PAD + 14 + 20 + 12) * density, cy * density - layout.size.height / 2f)
                for (s in tokens.textShadow) drawText(layout, color = tokens.text, topLeft = topLeft, shadow = s.scaled(density))
            }
        }
        // A menu whose rows act opens with none lit, so a stray tap never runs one: each row is its own tap target.
        menu.options.forEachIndexed { i, o ->
            Box(
                Modifier
                    .placeAt { Box(ComposerModel.MENU_PAD, ComposerModel.MENU_PAD + i * (ComposerModel.ROW + ComposerModel.ROW_GAP), (state.frame?.menuFinal?.w ?: 0f) - 2 * ComposerModel.MENU_PAD, ComposerModel.ROW) }
                    .semantics { contentDescription = o.label; role = Role.Button }
                    .clickable(remember { MutableInteractionSource() }, null, enabled = state.menuOpen) {
                        state.closeMenu()
                        menu.onPick(i)
                    },
            )
        }
    }
}

/** The drop's content clip: CSS `inset(top right bottom left round r)` in dp. */
private class ClipShape(val c: ClipInset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val l = c.left * d
        val t = c.top * d
        val r = (size.width - c.right * d).coerceAtLeast(l)
        val b = (size.height - c.bottom * d).coerceAtLeast(t)
        return Outline.Rounded(RoundRect(l, t, r, b, CornerRadius(c.radius * d)))
    }
}
