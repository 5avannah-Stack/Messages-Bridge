package cz.teous.liquid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.flow.first

/** One control with a hold menu, for the control's life. Input is in dp from the window's top left. */
@Stable
class HoldMenuState internal constructor() {
    internal val model = HoldMenuModel()
    internal var frame by mutableStateOf<HoldMenuFrame?>(null)
    internal var wake by mutableIntStateOf(0)
    /** The menu whose rows show: kept while its drop merges back, so the rows leave with their glass. */
    internal var shown by mutableStateOf<DockMenu?>(null)
    /** A menu [open] asked for, opened on the next frame, where it can be measured. */
    internal var request: DockMenu? = null
    /** The icon on the control, for the dock that draws this control in its own glass pass ([LiquidHoldMenu]'s `dock`). */
    internal var icon by mutableStateOf<ImageVector?>(null)

    /** True while a menu is open, until it starts merging back. */
    var menuOpen by mutableStateOf(false)
        internal set
    /** True while the menu was opened by the finger that is still down: rows are picked by lifting on them. */
    var held by mutableStateOf(false)
        internal set
    /** True while nothing moves: the frame loop sleeps and the glass isn't redrawn. */
    var idle by mutableStateOf(true)
        internal set

    /** The control's glass this frame, recoil included. */
    val control: GlassRect? get() = frame?.source
    /** The menu's resting rect in dp while one shows. */
    val menuRect: GlassRect? get() = frame?.menu?.final
    /** The row a finger at ([x], [y]) dp is over, as a lift there would pick it. */
    fun rowAt(x: Float, y: Float): Int? = model.rowAt(x, y)

    /**
     * Opens [menu] without a hold, such as from the control's tap: a tap on a row picks it, a tap anywhere else closes
     * it. A menu whose rows act should have no row selected, so a stray tap never runs one.
     */
    fun open(menu: DockMenu) { request = menu; wake++ }
    /** Closes the menu and picks nothing. */
    fun close() { request = null; model.close(); wake++ }

    /** Touch input, for an app that drives the control's touches itself or for checks. */
    fun press(x: Float, y: Float) = model.press(x, y).also { wake++ }
    fun move(x: Float, y: Float) { model.move(x, y); wake++ }
    fun release(x: Float, y: Float, cancelled: Boolean = false) { model.release(x, y, cancelled); wake++ }
}

@Composable
fun rememberHoldMenuState(): HoldMenuState = remember { HoldMenuState() }

/**
 * A control in liquid glass with a hold menu that is the dock's own: the menu leaves the control as one drop, on the
 * dock's numbers, recoil, lens and haptics (teodor-taste, Hold menus). It draws the control's glass from [source], its
 * rect in dp from the window's top left, as one body with the drop, and [icon] on it; null [source] hides both.
 *
 * Holding the control 360 ms opens [menu]; slide to a row and lift to pick it, lift anywhere else to close it. A shorter
 * press runs [onTap], which may open a menu by tap with [HoldMenuState.open]. TalkBack gets [label], the tap and each of
 * [menu]'s options as an action. Like [LiquidDock] it fills the window above a [backdropSource] and takes touches only
 * on the control and on an open menu. Draw a richer face (a digit, a picture) above it at the control's rect.
 *
 * With [dock], the control is a piece of that dock's glass: [LiquidDock] draws the control, its drop and its rows in
 * the dock's own pass, so the menu meets the dock exactly as the dock's own menu does, and a control that touches the
 * bar is one body with it. Every control on a screen with the dock joins it; [backdrop] and [glass] then go unused,
 * and the control shows only while that `LiquidDock` does. Without a dock on screen, the control draws its own glass.
 */
@Composable
fun LiquidHoldMenu(
    state: HoldMenuState,
    source: GlassRect?,
    label: String,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    menu: DockMenu? = null,
    icon: ImageVector? = null,
    onTap: () -> Unit = {},
    dock: DockState? = null,
    glass: GlassResources = rememberGlassResources(),
) {
    val tokens = LocalGlassTokens.current
    val view = LocalView.current
    val density by rememberUpdatedState(LocalDensity.current)
    val reduced by rememberUpdatedState(prefersReducedMotion())
    val measurer = rememberTextMeasurer()
    val latestSource by rememberUpdatedState(source)
    val latestMenu by rememberUpdatedState(menu)
    val latestOnTap by rememberUpdatedState(onTap)
    var width by remember { mutableStateOf(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun inputs() = HoldMenuInputs(width / density.density, latestSource, reduced)
    fun step(dtMs: Float) {
        state.request?.let { m ->
            state.request = null
            if (state.model.open(menuSize(m, measurer, density.density), m.selected)) state.shown = m
        }
        val f = state.model.frame(dtMs, inputs(), { latestMenu?.let { menuSize(it, measurer, density.density) } }) { latestMenu?.selected }
        state.frame = f
        for (e in state.model.take()) when (e) {
            is HoldMenuEvent.Feedback -> Haptics.perform(view, e.kind)
            is HoldMenuEvent.Opened -> if (e.held) state.shown = latestMenu
            is HoldMenuEvent.Picked -> state.shown?.onPick?.invoke(e.row)
            HoldMenuEvent.Tap -> latestOnTap()
        }
        state.menuOpen = state.model.menuOpen
        state.held = state.model.held
        state.idle = state.model.idle
    }

    // The frame loop runs only while something moves or a finger is down; at rest it waits for input or the control.
    LaunchedEffect(state) {
        snapshotFlow { width }.first { it > 0 }
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

    BackHandler(enabled = state.menuOpen) { state.close() }

    // Joined to a dock, the control is drawn by that dock, in the dock's glass pass, from this state's frame.
    SideEffect { state.icon = icon }
    if (dock != null) DisposableEffect(state, dock) {
        dock.joined += state
        onDispose { dock.joined -= state }
    }

    Box(modifier.fillMaxSize().onSizeChanged {
        width = it.width.toFloat()
        if (state.frame == null) step(0f)
    }.onPlaced { origin = it.positionInRoot() }) {
        if (dock == null) {
            Canvas(Modifier.fillMaxSize()) {
                val f = state.frame ?: return@Canvas
                drawLiquidGlass(f.glass, backdrop, glass, tokens, origin)
                drawHoldMenuControl(state, tokens)
            }
            HoldMenuRows(state, tokens)
        }
        if (source != null) ControlTouch(state, { latestSource }, label, menu, latestOnTap)
        if (state.menuOpen && !state.held) TapMenu(state)
    }
}

/** The control's icon at its centre, over its glass, from this frame. */
internal fun DrawScope.drawHoldMenuControl(state: HoldMenuState, tokens: GlassTokens) {
    val c = state.frame?.source ?: return
    val icon = state.icon ?: return
    drawLineIcon(icon, c.x + c.w / 2, c.y + c.h / 2, 1f, tokens.onGlass)
}

/** The rows of the control's menu while its drop shows, kept while the drop merges back, as the dock's own. */
@Composable
internal fun HoldMenuRows(state: HoldMenuState, tokens: GlassTokens) {
    val rows by remember(state) { derivedStateOf { state.shown?.takeIf { state.frame?.menu != null } } }
    rows?.let { m -> MenuRows(m.options, tokens) { state.frame?.menu } }
}

/** Follows each finger from down to lift, in dp from the window's top left, into [state], as the dock's touch does. */
private suspend fun PointerInputScope.follow(coords: () -> LayoutCoordinates?, state: HoldMenuState) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    fun root(o: Offset): Offset = (coords()?.localToRoot(o) ?: o) / density
    val at = root(down.position)
    if (!state.press(at.x, at.y)) return@awaitEachGesture
    down.consume()
    var lastAt = at
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) { state.release(lastAt.x, lastAt.y, cancelled = true); break }
        val p = root(change.position)
        lastAt = p
        change.consume()
        if (!change.pressed) { state.release(p.x, p.y); break }
        state.move(p.x, p.y)
    }
}

/** The control's touch target, with its name, its tap and its menu's options for TalkBack, which can't hold and slide. */
@Composable
private fun ControlTouch(state: HoldMenuState, source: () -> GlassRect?, label: String, menu: DockMenu?, onTap: () -> Unit) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        Modifier
            .placeAt { source()?.let(HoldMenuModel::hitBox) }
            .onPlaced { coords = it }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { onTap(); true }
                if (menu != null) customActions = menu.options.mapIndexed { row, o -> CustomAccessibilityAction(o.label) { menu.onPick(row); true } }
            }
            .pointerInput(state) { follow({ coords }, state) },
    )
}

/**
 * A menu opened without a hold takes every touch while it shows: a tap on a row picks it, a tap anywhere else closes
 * it. Each row is also a button for TalkBack.
 */
@Composable
private fun TapMenu(state: HoldMenuState) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(Modifier.fillMaxSize().onPlaced { coords = it }.pointerInput(state) { follow({ coords }, state) })
    val options = state.shown?.options ?: return
    options.forEachIndexed { i, o ->
        Box(
            Modifier
                .placeAt {
                    state.menuRect?.let { f -> Box(f.x + DockModel.MENU_PAD, f.y + DockModel.MENU_PAD + i * (DockModel.ROW + DockModel.ROW_GAP), f.w - 2 * DockModel.MENU_PAD, DockModel.ROW) }
                }
                .semantics {
                    contentDescription = o.label
                    role = Role.Button
                    onClick { state.model.choose(i); state.wake++; true }
                },
        )
    }
}
