package cz.teous.liquid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A dock cell: a line icon, or on the account cell the person's [picture], or their [initial] without one. A cell can
 * show what's going on in its part of the app (phone-apps dock.md, Signals): a [count] at its top right, such as updates
 * waiting, or a red [problem] dot there, such as backup storage full; [problem] is what TalkBack reads for it.
 * An [action] cell, such as New, shows no page: sliding across it selects nothing, and lifting on it runs
 * [LiquidDock]'s `onAction`.
 */
data class DockItem(
    val label: String, val icon: ImageVector? = null, val initial: String? = null, val picture: ImageBitmap? = null,
    val count: Int? = null, val problem: String? = null, val action: Boolean = false,
)

/** A hold menu: one row per option, picked by sliding the finger onto it and lifting. [selected] is the starting row. */
class DockMenu(val options: List<DockMenuOption>, val selected: Int? = null, val onPick: (Int) -> Unit)
class DockMenuOption(val label: String, val icon: ImageVector)

/**
 * The side bubble: a state worth showing beside the dock, such as the active filter or a running job. Pass it on every
 * page until the state clears; merging it back into the dock on a page change would move the whole dock. A job shows
 * its own icon (upload for an upload) and [progress] 0 to 1 lights the bubble's rim (Progress A); progress never draws
 * on a dock cell. Tapping it runs [onTap], which should clear the state.
 */
class DockBubble(val icon: ImageVector, val label: String, val progress: Float? = null, val onTap: () -> Unit)

/** What the bubble draws, compared by value, so a caller that recomposes doesn't redraw the glass. */
internal data class BubbleLook(val icon: ImageVector, val label: String, val progress: Float?)

/** Search in the dock's middle cell (Grow): the dock grows into this field. [onSubmit] runs on the keyboard's search key. */
class DockSearch(
    val query: TextFieldState, val placeholder: String, val closeLabel: String, val closeIcon: ImageVector,
    val onSubmit: (() -> Unit)? = null,
)

/**
 * A toast: [content] on a drop of the dock's glass, [width] × [height] dp, rising out of the dock and merging back when
 * it's gone (README, Drops). It waits while a hold menu or search has the dock. Pass it while it should show.
 */
class DockToast(val width: Float, val height: Float, val content: @Composable () -> Unit)

/**
 * Scrubbing by the dock, as Gallery's Photos scrubs through its years: holding [cell] still for 360 ms stretches the dock
 * into a timeline that shows only while held. Moving off the cell before then is the normal dock slide.
 * [onOpen] and [onMove] get the finger's place along the track, 0 at its left end and 1 at its right, also while the dock
 * is still stretching and past the ends. [onOpen] says whether there is anything to scrub (the dock confirms or turns
 * the hold down), [onMove] whether the finger crossed a mark worth a tick, such as a new year; [onClose] runs as the
 * finger lifts, and nothing plays then. The app sets what the timeline shows: its [marks], the place under the finger
 * ([at]), where the marks swell, the [label] written large below the line there, and the [caption] over the page above
 * the dock.
 */
@Stable
class DockScrub(
    val cell: Int,
    private val onOpen: (Float) -> Boolean,
    private val onMove: (Float) -> Boolean,
    private val onClose: () -> Unit,
) : Scrubber {
    var marks by mutableStateOf<List<ScrubMark>>(emptyList())
    var at by mutableFloatStateOf(0f)
    var label by mutableStateOf("")
    var caption by mutableStateOf("")

    override fun open(at: Float) = onOpen(at)
    override fun move(at: Float) = onMove(at)
    override fun close() = onClose()
}

/** A mark on a scrub's track at [at], 0 to 1, with a [label] (a year) on some; the marks between go unlabelled. */
@Immutable
data class ScrubMark(val at: Float, val label: String? = null)

/** One dock for the screen's life: keep it while pages change, or the lens stops sliding. */
@Stable
class DockState internal constructor(count: Int, searchCell: Int?, selected: Int) {
    internal var menus: (Int) -> DockMenu? = { null }
    internal var scrub: () -> DockScrub? = { null }
    internal var bubbleTap: (() -> Unit)? = null
    internal var actions: (Int) -> Boolean = { false }
    internal val model = DockModel(
        count, searchCell, selected, scrubber = { cell -> scrub()?.takeIf { it.cell == cell } }, hasMenu = { menus(it) != null }, isAction = { actions(it) },
    )
    internal var frame by mutableStateOf<DockFrame?>(null)
    internal val bubbleContent = BubbleContent<BubbleLook>()
    /** What the bubble draws this frame: held by [bubbleContent] until its glass is back in the dock. */
    internal var bubble by mutableStateOf<BubbleLook?>(null)
    internal var wake by mutableIntStateOf(0)
    internal var focusTick by mutableIntStateOf(0)
    /** Search's field has focus; a keyboard hidden while it does closes search. */
    internal var fieldFocused by mutableStateOf(false)
    /** The last [focusTick] the field acted on, so a field composed again (back from a viewer) doesn't raise the keyboard. */
    internal var focusDone = 0
    /** Controls with hold menus drawn in this dock's glass pass ([LiquidHoldMenu] with `dock`). */
    internal val joined = mutableStateListOf<HoldMenuState>()

    /** The page to show: the selected cell, or the page under Search while Search is selected. */
    var page by mutableIntStateOf(selected)
        internal set
    var searchOpen by mutableStateOf(false)
        internal set
    var menuOpen by mutableStateOf<Int?>(null)
        internal set
    /** A held cell has stretched the dock into its timeline ([DockScrub]). */
    var scrubbing by mutableStateOf(false)
        internal set
    /** True while nothing moves: the frame loop sleeps and the glass isn't redrawn. */
    var idle by mutableStateOf(true)
        internal set

    fun openSearch() { model.openSearch(); wake++ }
    fun closeSearch() { model.closeSearch(); wake++ }
    fun back(): Boolean = model.back().also { wake++ }
    fun show(cell: Int) { model.show(cell); wake++ }

    /** Touch input in dp from the window's top left: what a finger does, for checks that drive the dock directly. */
    fun press(x: Float, y: Float) = model.press(x, y).also { wake++ }
    fun move(x: Float, y: Float) { model.move(x, y); wake++ }
    fun release(x: Float, y: Float, cancelled: Boolean = false) { model.release(x, y, cancelled); wake++ }

    /** The dock's bar in dp, once it has been laid out. */
    val bar: Box? get() = frame?.dock
    /** The dock's own piece of glass this frame, recoil included. */
    val dockShape: GlassRect? get() = frame?.glass?.shapes?.firstOrNull()
    /** The hold menu's resting rect in dp while one shows. */
    val menuRect: GlassRect? get() = frame?.menu?.final
}

@Composable
fun rememberDockState(count: Int, searchCell: Int? = null, selected: Int = 0): DockState =
    remember(count, searchCell) { DockState(count, searchCell, selected) }

/**
 * The phone dock in liquid glass (dock-lab.html with its default options). It fills the window, above a [backdropSource]
 * that holds the pages and [DockSearchLayer]. It draws the dock, its lens and icons, the side bubble, the hold menus
 * and the search field, and takes touches only on the dock and on what it shows.
 */
@Composable
fun LiquidDock(
    state: DockState,
    items: List<DockItem>,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    menus: (Int) -> DockMenu? = { null },
    bubble: DockBubble? = null,
    search: DockSearch? = null,
    toast: DockToast? = null,
    scrub: DockScrub? = null,
    onPage: (Int) -> Unit = {},
    onAction: (Int) -> Unit = {},
    glass: GlassResources = rememberGlassResources(),
) {
    val tokens = LocalGlassTokens.current
    val view = LocalView.current
    val density by rememberUpdatedState(LocalDensity.current)
    val reduced by rememberUpdatedState(prefersReducedMotion())
    val nav = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val measurer = rememberTextMeasurer()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestMenus by rememberUpdatedState(menus)
    val latestBubble by rememberUpdatedState(bubble)
    val latestItems by rememberUpdatedState(items)
    val latestOnPage by rememberUpdatedState(onPage)
    val latestOnAction by rememberUpdatedState(onAction)
    val latestToast by rememberUpdatedState(toast)
    val latestScrub by rememberUpdatedState(scrub)
    state.menus = { latestMenus(it) }
    state.actions = { latestItems.getOrNull(it)?.action == true }
    state.scrub = { latestScrub }
    state.bubbleTap = { latestBubble?.onTap?.invoke() }
    // What the draw reads besides the frame, compared by value, so a caller that recomposes doesn't redraw the glass.
    val looks by remember { derivedStateOf { latestItems.map { CellLook(it.icon, it.initial, it.picture, it.count, it.problem != null) } } }
    val look by remember { derivedStateOf { latestBubble?.let { BubbleLook(it.icon, it.label, it.progress) } } }
    var size by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun inputs(): DockInputs {
        val d = density.density
        val b = latestBubble
        return DockInputs(
            width = size.width / d, height = size.height / d,
            safeBottom = nav.getBottom(density) / d, ime = ime.getBottom(density) / d, reduced = reduced,
            bubble = b != null, toast = latestToast?.let { Size2(it.width, it.height) }, fieldFocused = state.fieldFocused,
        )
    }
    fun step(dtMs: Float) {
        val f = state.model.frame(dtMs, inputs(), { cell -> latestMenus(cell)?.let { menuSize(it, measurer, density.density) } ?: MenuSize(0, 0f) }) { latestMenus(it)?.selected }
        state.frame = f
        state.bubble = state.bubbleContent.update(look, f)
        for (e in state.model.take()) when (e) {
            is DockEvent.Feedback -> Haptics.perform(view, e.kind)
            is DockEvent.Page -> { state.page = e.page; latestOnPage(e.page) }
            is DockEvent.MenuPicked -> latestMenus(e.cell)?.onPick?.invoke(e.row)
            is DockEvent.Action -> latestOnAction(e.cell)
            is DockEvent.SearchOpened -> if (e.keyboard) state.focusTick++
            DockEvent.SearchClosed -> { focusManager.clearFocus(); keyboard?.hide() }
        }
        state.searchOpen = state.model.searchOpen
        state.menuOpen = state.model.menuOpen
        state.scrubbing = state.model.scrubbing
        state.idle = state.model.idle
    }

    // The frame loop runs only while something moves or a finger is down. At rest it waits for input or for what it
    // shows to change, so resting glass is drawn once and then left alone.
    LaunchedEffect(state) {
        snapshotFlow { size }.first { it.width > 0 }
        var last = 0L
        step(0f)
        while (true) {
            if (state.model.idle) {
                val key = Triple(state.wake, inputs(), look)
                snapshotFlow { Triple(state.wake, inputs(), look) }.first { it != key }
                last = 0L
            }
            withFrameNanos { t ->
                val dt = if (last == 0L) frameMs(view) else (t - last) / 1e6f
                last = t
                step(dt)
            }
        }
    }

    // A dock taken away (an action's panel, a viewer) forgets its last frame and puts the lens back on the page, so when it
    // comes back its first frame is worked out afresh in layout and never shows the lens where it was left.
    DisposableEffect(state) { onDispose { state.model.restLens(); state.frame = null } }

    BackHandler(enabled = state.searchOpen || state.menuOpen != null) { state.back() }

    // The first frame is worked out in the same layout pass that learns the window's size, so the dock draws in the
    // first frame it appears in, such as when it takes over from the composer.
    Box(modifier.fillMaxSize().onSizeChanged {
        size = Size(it.width.toFloat(), it.height.toFloat())
        if (state.frame == null) step(0f)
    }.onPlaced { origin = it.positionInRoot() }) {
        // The glass, lens, icons and the bubble's content: one draw that reads only the frame and what the bubble holds.
        Canvas(Modifier.fillMaxSize()) {
            val f = state.frame ?: return@Canvas
            // Joined controls and their drops go to the glass with the dock's pieces, so one pass draws them all: a
            // control's menu meets the bar as the dock's own does, and a control that touches the bar is one body with it.
            val frames = state.joined.mapNotNull { it.frame }
            val outline = if (frames.isEmpty()) f.glass
            else GlassOutline(f.glass.shapes + frames.flatMap { it.glass.shapes }, f.glass.bridges + frames.flatMap { it.glass.bridges })
            drawLiquidGlass(outline, backdrop, glass, tokens, origin)
            for (s in state.joined) drawHoldMenuControl(s, tokens)
            drawLens(f.lens, tokens, radius = 21f)
            f.icons.forEachIndexed { i, ic -> looks.getOrNull(i)?.let { drawDockIcon(it, ic, tokens, measurer) } }
            val b = f.bubble
            val content = state.bubble
            if (b != null && content != null && b.contentAlpha > .001f) {
                // The ring sits on an r 23 circle in the 50 dp box, stretched with the bubble and faded with its icon,
                // as the lab's SVG inside the bubble's content.
                content.progress?.let { drawRing(b.rect.x + b.rect.w / 2, b.rect.y + b.rect.h / 2, 23f * b.rect.w / 50, 23f * b.rect.h / 50, it, b.contentAlpha, b.rect.w / 50) }
                drawLineIcon(content.icon, b.rect.x + b.rect.w / 2, b.rect.y + b.rect.h / 2, 1f, tokens.onGlass.copy(alpha = b.contentAlpha))
            }
        }
        if (scrub != null) ScrubLayer(state, scrub, tokens)
        DockMenuLayer(state, tokens)
        for (s in state.joined) key(s) { HoldMenuRows(s, tokens) }
        ToastLayer(state, toast)
        if (search != null) SearchField(state, search, tokens)
        BubbleTarget(state, state.bubble?.label)
        DockTouch(state, items, latestMenus)
    }
}

private val MenuText = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

/** A hold menu's size, measured in the bold weight the current row takes, so no row ever runs out of room. */
internal fun menuSize(menu: DockMenu, measurer: TextMeasurer, density: Float): MenuSize {
    val text = menu.options.maxOf { measurer.measure(it.label, MenuText.copy(fontWeight = FontWeight.SemiBold)).size.width } / density
    return MenuSize(menu.options.size, 14 + 20 + 12 + text + 20)
}

/** Search's dim and results, which sit under the glass: put it inside the [backdropSource], over the page. */
@Composable
fun DockSearchLayer(state: DockState, results: @Composable () -> Unit) {
    val shown by remember(state) { derivedStateOf { state.searchOpen || (state.frame?.dim ?: 0f) > 0f } }
    if (!shown) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (state.frame?.dim ?: 0f) }
                .background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null, enabled = state.searchOpen) { state.closeSearch() },
        )
        Box(
            Modifier.fillMaxSize()
                .layout { m, c ->
                    val bottom = ((state.frame?.resultsBottom ?: 0f) * density).roundToInt()
                    val p = m.measure(Constraints.fixed(c.maxWidth, (c.maxHeight - bottom).coerceAtLeast(0)))
                    layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                }
                .graphicsLayer { alpha = state.frame?.resultsAlpha ?: 0f },
        ) { results() }
    }
}

/** A box placed at a rect in dp from the window's top left, or nothing while [rect] is null. Read in layout only. */
internal fun Modifier.placeAt(rect: () -> Box?) = layout { m, _ ->
    val r = rect()
    if (r == null) {
        val p = m.measure(Constraints.fixed(0, 0))
        return@layout layout(0, 0) { p.place(0, 0) }
    }
    val p = m.measure(Constraints.fixed((r.w * density).roundToInt().coerceAtLeast(0), (r.h * density).roundToInt().coerceAtLeast(0)))
    layout(p.width, p.height) { p.place((r.x * density).roundToInt(), (r.y * density).roundToInt()) }
}

/**
 * The dock's touch area: the bar plus 3 dp above and 16 dp below, with one accessible tab per cell. A cell with a hold
 * menu also offers each of its options as an accessibility action, since TalkBack can't hold and slide.
 */
@Composable
private fun DockTouch(state: DockState, items: List<DockItem>, menus: (Int) -> DockMenu?) {
    // Gone while search has the dock: laid out at 0 x 0, the cells would still be tabs with a tap in the top left.
    val shown by remember(state) { derivedStateOf { state.frame?.let { !it.searchOpen } == true } }
    if (!shown) return
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier
            .placeAt { state.frame?.takeIf { !it.searchOpen }?.hit }
            .onPlaced { coords = it }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fun root(o: Offset): Offset = (coords?.localToRoot(o) ?: o) / density
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
            },
    ) {
        items.forEachIndexed { i, item ->
            Box(
                Modifier.weight(1f).fillMaxHeight().semantics {
                    role = if (item.action) Role.Button else Role.Tab
                    contentDescription = item.label
                    (item.problem ?: item.count?.toString())?.let { stateDescription = it }
                    if (!item.action) selected = state.page == i && !state.searchOpen
                    onClick { state.model.activate(i); state.wake++; true }
                    menus(i)?.let { menu ->
                        customActions = menu.options.mapIndexed { row, o -> CustomAccessibilityAction(o.label) { menu.onPick(row); true } }
                    }
                },
            )
        }
    }
}

/**
 * The side bubble's tap target, over the bubble once it has left the dock. It exists only while the bubble is out: a
 * clickable laid out at 0 x 0 still takes touches over a 48 dp minimum, which left an unnamed target in the top left.
 */
@Composable
private fun BubbleTarget(state: DockState, label: String?) {
    val view = LocalView.current
    val out by remember(state) { derivedStateOf { state.frame?.bubble?.rect != null } }
    if (!out) return
    Box(
        Modifier
            .placeAt { state.frame?.bubble?.rect }
            .semantics { if (label != null) contentDescription = label }
            .clickable(remember { MutableInteractionSource() }, null) {
                Haptics.perform(view, Haptic.Confirm)
                state.bubbleTap?.invoke()
            },
    )
}

/** The toast's content at its final place, clipped to the drop as it grows; it stays while the drop merges back. */
@Composable
private fun ToastLayer(state: DockState, toast: DockToast?) {
    var shown by remember { mutableStateOf<DockToast?>(null) }
    if (toast != null) shown = toast
    val visible by remember(state) { derivedStateOf { state.frame?.toast != null } }
    val content = shown ?: return
    if (!visible) return
    Box(
        Modifier
            .placeAt { state.frame?.toast?.final?.let { Box(it.x, it.y, it.w, it.h) } }
            .graphicsLayer {
                val t = state.frame?.toast
                alpha = t?.alpha ?: 0f
                clip = true
                shape = t?.let { InsetShape(it.clip) } ?: RectangleShape
            },
    ) { content.content() }
}

/**
 * A scrub's timeline in the stretched dock (Gallery's marker D): a faint line and a mark per [ScrubMark], labelled on
 * some. The marks swell upwards near the finger like a magnifier, with no ring or bubble; the label under the finger is
 * written large below the line, and the caption floats as text over the page above the dock, centred on the finger.
 * A layer of its own, so the timeline changing under the finger never redraws the glass.
 * Everything fades through a layer's alpha, never a draw's: drawText's alpha leaves the text's halo at full strength,
 * which drew the labels' dark shadows outside the dock from the first frame of the stretch. The timeline is clipped to
 * the bar's rounded outline, not its box, and fades out over [SCRUB_FADE] dp at each end, so as the glass stretches the
 * labels come out of its ends instead of showing past them.
 */
@Composable
private fun ScrubLayer(state: DockState, scrub: DockScrub, tokens: GlassTokens) {
    val measurer = rememberTextMeasurer()
    val shown by remember(state) { derivedStateOf { state.frame?.scrub != null } }
    if (!shown) return
    Canvas(Modifier.fillMaxSize()) {
        val sd = state.frame?.scrub ?: return@Canvas
        val d = density
        val x = { at: Float -> sd.left + (sd.right - sd.left) * at }
        val lens = x(scrub.at)
        val y = sd.y
        // Text over the glass and the page wears the theme's halo, as menu rows do. [left] and [top] in dp.
        fun text(layout: TextLayoutResult, color: Color, left: Float, top: Float) {
            for (sh in tokens.textShadow) drawText(layout, color = color, topLeft = Offset(left * d, top * d), shadow = sh.scaled(d))
        }
        val canvas = drawContext.canvas
        val faded = Paint().apply { alpha = sd.alpha }
        val bar = sd.bar
        val box = Rect(bar.x * d, bar.y * d, (bar.x + bar.w) * d, (bar.y + bar.h) * d)
        canvas.saveLayer(box, faded)
        clipPath(Path().apply { addRoundRect(RoundRect(box, CornerRadius(bar.h / 2 * d))) }) {
            drawLine(Color.White.copy(alpha = .14f), Offset(x(0f) * d, y * d), Offset(x(1f) * d, y * d), 2 * d, StrokeCap.Round)
            var lastLabel = Float.NEGATIVE_INFINITY
            for (m in scrub.marks.sortedBy { it.at }) {
                val at = x(m.at)
                val near = (1 - abs(at - lens) / 46f).coerceAtLeast(0f)
                val half = if (m.label != null) 6f else 4f
                val alpha = (if (m.label != null) .55f else .3f) + .45f * near
                drawLine(Color.White.copy(alpha = alpha), Offset(at * d, (y - half - 10 * near) * d), Offset(at * d, (y + half) * d), 2 * d, StrokeCap.Round)
                // A label shows where it has room and isn't under the finger, where the large one is.
                if (m.label != null && at - lastLabel > 30 && abs(at - lens) > 26) {
                    val layout = measurer.measure(m.label, ScrubMarkText)
                    text(layout, tokens.text3, at - layout.size.width / d / 2, y + 9)
                    lastLabel = at
                }
            }
            if (scrub.label.isNotEmpty()) {
                val layout = measurer.measure(scrub.label, ScrubLabelText)
                val w = layout.size.width / d
                text(layout, tokens.text, (lens - w / 2).coerceIn(bar.x, (bar.x + bar.w - w).coerceAtLeast(bar.x)), y + 8)
            }
        }
        // The ends: whatever reaches the stretching glass's ends fades into them.
        val edge = (SCRUB_FADE / bar.w).coerceAtMost(.5f)
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1 - edge to Color.Black, 1f to Color.Transparent, startX = box.left, endX = box.right),
            box.topLeft, box.size, blendMode = BlendMode.DstIn,
        )
        canvas.restore()
        if (scrub.caption.isNotEmpty()) {
            val layout = measurer.measure(scrub.caption, ScrubCaptionText)
            val w = layout.size.width / d
            canvas.saveLayer(Rect(Offset.Zero, size), faded)
            text(layout, tokens.text, (lens - w / 2).coerceIn(12f, (size.width / d - w - 12).coerceAtLeast(12f)), bar.y - 12 - layout.size.height / d)
            canvas.restore()
        }
    }
}

/** How far in from each end of the stretching dock the timeline fades into the glass, in dp. */
private const val SCRUB_FADE = 22f
private val ScrubMarkText = TextStyle(fontSize = 10.sp)
private val ScrubLabelText = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
private val ScrubCaptionText = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

/** The hold menu's rows at their final place, clipped to the drop as it grows and faded in as it lands. */
@Composable
private fun DockMenuLayer(state: DockState, tokens: GlassTokens) {
    val cell by remember(state) { derivedStateOf { state.frame?.menu?.cell } }
    val menu = cell?.let { state.menus(it) } ?: return
    MenuRows(menu.options, tokens) { state.frame?.menu?.let { HoldMenuDraw(it.final, it.clip, it.alpha, it.lens, it.row) } }
}

/**
 * A hold menu's rows, for the dock and every other control's menu: at the menu's resting place, clipped to the drop as
 * it grows and faded in as it lands, with the lens on the lit row. [draw] is read in layout and draw only.
 */
@Composable
internal fun MenuRows(options: List<DockMenuOption>, tokens: GlassTokens, draw: () -> HoldMenuDraw?) {
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .placeAt { draw()?.final?.let { Box(it.x, it.y, it.w, it.h) } }
            .graphicsLayer {
                val md = draw()
                alpha = md?.alpha ?: 0f
                clip = true
                shape = md?.let { InsetShape(it.clip) } ?: RectangleShape
            },
    ) {
        val md = draw() ?: return@Canvas
        md.lens?.let { drawLens(it, tokens) }
        options.forEachIndexed { i, o ->
            val cy = DockModel.MENU_PAD + i * (DockModel.ROW + DockModel.ROW_GAP) + DockModel.ROW / 2
            drawLineIcon(o.icon, DockModel.MENU_PAD + 14 + 10, cy, 1f, tokens.text, size = 20f)
            val style = MenuText.copy(color = tokens.text, fontWeight = if (i == md.row) FontWeight.SemiBold else FontWeight.Medium)
            val layout = measurer.measure(o.label, style)
            val topLeft = Offset((DockModel.MENU_PAD + 14 + 20 + 12) * density, cy * density - layout.size.height / 2f)
            for (s in tokens.textShadow) drawText(layout, color = tokens.text, topLeft = topLeft, shadow = s.scaled(density))
        }
    }
}

/** A CSS text-shadow in dp as Android's shadow layer, whose radius maps to sigma as a blur radius does. */
internal fun Shadow.scaled(density: Float) = Shadow(color, Offset(offset.x * density, offset.y * density), sigmaToRadius(blurRadius / 2 * density))

/** The drop's content clip: CSS `inset(top right bottom left round r)` in dp. */
internal class InsetShape(val c: ClipInset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val l = c.left * d
        val t = c.top * d
        val r = (size.width - c.right * d).coerceAtLeast(l)
        val b = (size.height - c.bottom * d).coerceAtLeast(t)
        return Outline.Rounded(RoundRect(l, t, r, b, CornerRadius(c.radius * d)))
    }
}

/** The field the dock grows into: the query, its placeholder and caret, and the close button. */
@Composable
private fun SearchField(state: DockState, search: DockSearch, tokens: GlassTokens) {
    val shown by remember(state) { derivedStateOf { state.searchOpen || (state.frame?.field?.textAlpha ?: 0f) > 0f } }
    if (!shown) return
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A field that leaves (search closed, or the dock taken away under a viewer) has no focus, whatever it last reported,
    // so coming back with the keyboard down never reads as the keyboard being dismissed.
    DisposableEffect(state) { onDispose { state.fieldFocused = false } }
    // Focus only once the field is in the tree: the keyboard rises and the field rides it.
    LaunchedEffect(state.focusTick) {
        if (state.focusTick > state.focusDone && state.searchOpen) { state.focusDone = state.focusTick; focus.requestFocus(); keyboard?.show() }
    }
    val style = TextStyle(color = tokens.text, fontSize = 16.sp, shadow = tokens.textShadow.last().scaled(density))
    Box(
        Modifier
            .placeAt { state.frame?.field?.let { Box(it.textX, it.field.y, it.textW, it.field.h) } }
            .graphicsLayer { alpha = state.frame?.field?.textAlpha ?: 0f },
    ) {
        BasicTextField(
            state = search.query,
            modifier = Modifier.fillMaxSize().focusRequester(focus).onFocusChanged { state.fieldFocused = it.isFocused }
                .semantics { contentDescription = search.placeholder },
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = if (search.onSubmit != null) ImeAction.Search else ImeAction.Default),
            onKeyboardAction = { search.onSubmit?.invoke() },
            decorator = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (search.query.text.isEmpty()) BasicText(search.placeholder, style = style.copy(color = tokens.text3))
                    inner()
                }
            },
        )
    }
    if (state.searchOpen) {
        Canvas(
            Modifier
                .placeAt { state.frame?.field?.let { Box(it.closeX, it.closeY, 40f, 40f) } }
                .graphicsLayer { alpha = state.frame?.field?.closeAlpha ?: 0f }
                .semantics { contentDescription = search.closeLabel }
                .clickable(remember { MutableInteractionSource() }, null) { state.closeSearch() },
        ) { drawLineIcon(search.closeIcon, 20f, 20f, 1f, tokens.text2, size = 20f) }
    }
}

/* ---------- Drawing helpers, in dp ---------- */

/** What a cell draws, compared by value, so a caller that recomposes doesn't redraw the glass. */
internal data class CellLook(val icon: ImageVector?, val initial: String?, val picture: ImageBitmap?, val count: Int? = null, val problem: Boolean = false)

internal fun DrawScope.drawDockIcon(look: CellLook, ic: IconDraw, tokens: GlassTokens, measurer: TextMeasurer) {
    if (ic.alpha <= .001f) return
    val d = density
    val (icon, initial, picture) = look
    drawSignal(look, ic, tokens, measurer)
    if (picture != null) {
        // The account cell: the person's picture on a 26 dp disc, growing under the lens like the icons.
        withTransform({ scale(ic.scale, ic.scale, Offset(ic.x * d, ic.y * d)) }) {
            val r = 13 * d
            val clip = Path().apply { addOval(Rect(Offset(ic.x * d, ic.y * d), r)) }
            clipPath(clip) {
                drawImage(picture, dstOffset = IntOffset((ic.x * d - r).roundToInt(), (ic.y * d - r).roundToInt()), dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()), alpha = ic.alpha)
            }
        }
    } else if (initial != null) {
        // The account cell: the person's initial on a 26 dp disc, brightening under the lens.
        withTransform({ scale(ic.scale, ic.scale, Offset(ic.x * d, ic.y * d)) }) {
            val a = (.8f + .2f * ic.near) * ic.alpha
            drawCircle(Color(0xFF2C2C2E).copy(alpha = a), 13 * d, Offset(ic.x * d, ic.y * d))
            val layout = measurer.measure(initial, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold))
            drawText(layout, color = tokens.onGlass.copy(alpha = a), topLeft = Offset(ic.x * d - layout.size.width / 2f, ic.y * d - layout.size.height / 2f))
        }
    } else if (icon != null) drawLineIcon(icon, ic.x, ic.y, ic.scale, Color.White.copy(alpha = lerp(tokens.iconDim, 1f, ic.near) * ic.alpha))
}

/**
 * A cell's signal at its top right, faded and magnified with its icon (the elements lab's pick, #16): a problem is a red
 * 7 dp dot with a 2 dp dark ring, 12.5 dp right of and 9.5 dp above the cell's centre; a count is an 18 dp white pill
 * at 90 %, 3 dp in from the cell's right and 2 dp from its top, holding the number in 11 sp bold black, all at .86. A problem takes the place of a count. Drawn under the icon.
 */
private fun DrawScope.drawSignal(look: CellLook, ic: IconDraw, tokens: GlassTokens, measurer: TextMeasurer) {
    if (!look.problem && look.count == null) return
    val d = density
    withTransform({ scale(ic.scale, ic.scale, Offset(ic.x * d, ic.y * d)) }) {
        if (look.problem) {
            val c = Offset((ic.x + 12.5f) * d, (ic.y - 9.5f) * d)
            drawCircle(Color.Black.copy(alpha = .5f * ic.alpha), 5.5f * d, c)
            drawCircle(tokens.red.copy(alpha = ic.alpha), 3.5f * d, c)
        } else {
            // The lab's CSS: top 2 px and right 3 px in a 50 × 42 cell, then scale(.86) about the pill's own centre.
            val text = measurer.measure(look.count.toString(), TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val w = maxOf(18f, text.size.width / d + 10f)
            val c = Offset((ic.x + 22f - w / 2) * d, (ic.y - 10f) * d)
            withTransform({ scale(.86f, .86f, c) }) {
                drawRoundRect(Color.White.copy(alpha = .9f * ic.alpha), Offset(c.x - w / 2 * d, c.y - 9f * d), Size(w * d, 18f * d), CornerRadius(9f * d))
                drawText(text, color = Color.Black.copy(alpha = ic.alpha), topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
            }
        }
    }
}

/**
 * Draws a line icon from its own paths, scaled about its centre at ([cx], [cy]) dp, so it stays sharp while it
 * magnifies. [size] is the icon's box in dp (the dock's 22).
 */
fun DrawScope.drawLineIcon(icon: ImageVector, cx: Float, cy: Float, scale: Float, color: Color, size: Float = 22f) {
    val d = density
    val k = size * d / icon.viewportWidth * scale
    withTransform({
        translate(cx * d - icon.viewportWidth * k / 2, cy * d - icon.viewportHeight * k / 2)
        scale(k, k, Offset.Zero)
    }) { drawGroup(icon.root, color) }
}

private fun DrawScope.drawGroup(group: VectorGroup, color: Color) {
    for (node in group) when (node) {
        is VectorGroup -> withTransform({
            translate(node.translationX, node.translationY)
            rotate(node.rotation, Offset(node.pivotX, node.pivotY))
            scale(node.scaleX, node.scaleY, Offset(node.pivotX, node.pivotY))
        }) { drawGroup(node, color) }
        is VectorPath -> {
            val path = node.pathData.toPath()
            if (node.fill != null) drawPath(path, color)
            if (node.stroke != null) drawPath(path, color, style = Stroke(node.strokeLineWidth, cap = node.strokeLineCap, join = node.strokeLineJoin))
        }
    }
}

/**
 * Progress A: light filling around the side bubble's rim, clockwise from the top: a soft band (.14, 4 dp) under a thin line (.62,
 * 1.2 dp), on an ellipse with radii [rx] and [ry] dp. [stroke] scales both widths, as the bubble's stretched SVG does.
 */
private fun DrawScope.drawRing(cx: Float, cy: Float, rx: Float, ry: Float, progress: Float, alpha: Float, stroke: Float = 1f) {
    if (alpha <= .001f || progress <= 0f) return
    val d = density
    val topLeft = Offset((cx - rx) * d, (cy - ry) * d)
    val size = Size(2 * rx * d, 2 * ry * d)
    val sweep = 360f * progress.coerceIn(0f, 1f)
    drawArc(Color.White.copy(alpha = .14f * alpha), -90f, sweep, false, topLeft, size, style = Stroke(4f * d * stroke, cap = StrokeCap.Round))
    drawArc(Color.White.copy(alpha = .62f * alpha), -90f, sweep, false, topLeft, size, style = Stroke(1.2f * d * stroke, cap = StrokeCap.Round))
}
