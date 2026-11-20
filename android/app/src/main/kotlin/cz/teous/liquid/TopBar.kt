package cz.teous.liquid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* ---------- The top bar: the composer's twin at the top of a page (README, Top bar) ---------- */

/**
 * What the top bar is laid out from in a frame, in dp. [leftW] is the left piece's width, [centerW] the centre's
 * contents, null without a centre, and [actionsW] each action's piece, left to right. [period] is one frame of the
 * display in ms, which caps the close's first frames.
 */
data class TopBarInputs(
    val width: Float, val safeTop: Float, val reduced: Boolean,
    val leftW: Float = TopBarModel.H, val centerW: Float? = null, val actionsW: List<Float> = emptyList(),
    val period: Float = 1000f / 60,
)

/** What the top bar draws in a frame, in dp from the window's top left. */
data class TopBarFrame(
    val glass: GlassOutline, val left: Box, val center: Box?, val actions: List<Box>,
    /** Back's and the actions' icons: they show once the pieces have parted. */
    val contentAlpha: Float,
    /** The centre capsule's face and name: they show as it finishes blooming, before back and the actions part. */
    val centerAlpha: Float = 0f,
    /** The open menu's rows, while its drop shows. */
    val menu: HoldMenuDraw? = null,
)

/**
 * The top bar, with no Android types so it runs in unit tests. Opening, the centre capsule blooms where it stays, out of
 * nothing at its middle, and back and the actions split out of its ends as the composer's pieces split out of the dock:
 * on the composer's spring, joined by necks that thin and snap as they part. A bar with no centre blooms a drop in the
 * middle that splits into its pieces and is gone. [open] false runs it back: the pieces join into the capsule, which
 * shrinks back into nothing. So a chat's top and its composer split and close together, one at each edge.
 * An action or the centre capsule can open a menu: the dock's hold menu ([MenuDrop]), dropping downward, centred under
 * its piece and pushed in from the screen's edges (menu-lab.html).
 */
class TopBarModel {
    var open = true
        set(value) {
            if (value != field) sinceTurn = 0f
            field = value
            if (!value) menu.hide()
        }
    // The centre's bloom, and the split of back and the actions out of it, which follows SPLIT_AFTER later.
    private val bloom = Spring(0f, BLOOM_RESPONSE, BLOOM_DAMPING)
    private val p = Spring(0f, ComposerModel.OPEN_RESPONSE, ComposerModel.OPEN_DAMPING)
    // The centre's and each action's width, so a name that loads late or Edit turning into Done widens its piece on a
    // spring instead of in a frame.
    private val centerW = Spring(0f, WIDTH_RESPONSE, WIDTH_DAMPING)
    private val actionsW = ArrayList<Spring>()
    private val menu = MenuDrop()
    private val recoil = Spring(0f, .3f, .55f)
    /** The piece the menu leaves: [CENTER] for the centre capsule, 0 and up for an action. */
    var menuOf = 0
        private set
    // Real time since the last turn (open or close), to find the close's first frames.
    private var sinceTurn = 0f
    private val springs get() = listOf(bloom, p, centerW, recoil) + actionsW
    private var hidden = true

    val idle get() = springs.all { it.resting } && menu.resting
    /** True once a closing bar has shrunk out of sight. */
    val closed get() = !open && hidden
    val menuOpen get() = menu.open

    /** Opens [size]'s menu out of piece [of] ([CENTER] or an action's index), with no row lit: its rows act. */
    fun openMenu(of: Int, size: MenuSize) {
        menuOf = of
        menu.show(size, null)
    }
    /** Closes the menu; its drop merges back into its piece. */
    fun closeMenu() = menu.hide()
    /** The menu's row under ([x], [y]) in dp, from its resting place. */
    fun rowAt(x: Float, y: Float) = menu.rowAt(x, y)

    /** [dt] in ms since the last frame. */
    fun frame(dt: Float, i: TopBarInputs): TopBarFrame {
        // Opening, the capsule blooms first and the split follows; closing, the pieces join first and the capsule
        // shrinks after. A turn while the other half is still under way doesn't wait for it.
        if (open) {
            bloom.tune(BLOOM_RESPONSE, BLOOM_DAMPING)
            p.tune(ComposerModel.OPEN_RESPONSE, ComposerModel.OPEN_DAMPING)
            bloom.t = 1f
            if (sinceTurn >= SPLIT_AFTER || bloom.x > .9f || i.reduced) p.t = 1f
        } else {
            bloom.tune(SHRINK_RESPONSE, SHRINK_DAMPING)
            p.tune(JOIN_RESPONSE, JOIN_DAMPING)
            p.t = 0f
            if (sinceTurn >= SHRINK_AFTER || p.x < JOINED || i.reduced) bloom.t = 0f
        }
        centerW.t = i.centerW ?: 0f
        // A bar whose actions change in number takes their new widths at once.
        if (actionsW.size != i.actionsW.size) { actionsW.clear(); i.actionsW.forEach { actionsW += Spring(it, WIDTH_RESPONSE, WIDTH_DAMPING) } }
        actionsW.forEachIndexed { k, sp -> sp.t = i.actionsW[k] }
        // As the composer: a frame longer than 50 ms moves no further than one of 50 ms, and the close's first frames,
        // in which the app composes the page it goes back to, advance by one frame of the display at most.
        val raw = min(50f, max(0f, dt))
        val ms = if (!open && sinceTurn < ComposerModel.CLOSE_GRACE) min(raw, i.period) else raw
        sinceTurn += raw
        val sec = ms / 1000
        for (s in springs) s.step(sec, i.reduced)
        menu.step(sec, i.reduced)
        // While the bar is out of sight its pieces take their contents' widths at once, so it opens at its size.
        if (hidden || i.reduced) { centerW.snap(centerW.t); actionsW.forEach { it.snap(it.t) } }

        val top = i.safeTop + TOP
        val left1 = Box(EDGE, top, i.leftW, H)
        // Each action is a piece of its own, GAP apart, the last one EDGE in from the screen's right edge.
        val actions1 = ArrayList<Box>(actionsW.size)
        var x = i.width - EDGE - actionsW.sumOf { it.x.toDouble() }.toFloat() - GAP * max(0, actionsW.size - 1)
        for (sp in actionsW) { actions1 += Box(x, top, sp.x, H); x += sp.x + GAP }
        val rightEdge = actions1.firstOrNull()?.x ?: (i.width - EDGE)
        // The centre capsule is centred on the window, and kept clear of the pieces beside it.
        val center1 = i.centerW?.let {
            val lo = left1.x + left1.w + GAP
            val hi = rightEdge - GAP
            val w = max(H, min(centerW.x, hi - lo))
            Box(((i.width - w) / 2).coerceIn(lo, max(lo, hi - w)), top, w, H)
        }
        // The capsule blooms about its middle: its height fills first, then its width, which overshoots with the spring.
        // Every piece is as high as the capsule is now, so where they lie in each other they are one body (oneCapsule).
        // A bar with no centre blooms a drop of 2 H in the middle, which shrinks away as the pieces leave it.
        val b = bloom.x
        val t = p.x
        val q = c01(t)
        val hd = H * c01(b / FILL)
        val y = top + (H - hd) / 2
        val wide = if (b > 1) b else c01((b - .1f) / .9f)
        val home = center1 ?: Box(i.width / 2 - H, top, 2 * H, H)
        val cx = home.x + home.w / 2
        val bloomed = if (center1 != null) {
            val w = max(hd, home.w * wide)
            Box(cx - w / 2, y, w, hd)
        } else {
            val w = home.w * wide * (1 - smooth(0f, .6f, t))
            val h = min(hd, w)
            Box(cx - w / 2, top + (H - h) / 2, w, h)
        }
        // Back and the actions start inside the capsule's ends as it is now and run out to their places, growing to
        // their own width.
        fun out(r: Box, x0: Float) = Box(lerp(x0, r.x, t), y, max(hd, lerp(hd, r.w, q)), hd)
        val left = out(left1, bloomed.x)
        val center = if (center1 != null) bloomed else null
        val actions = actions1.map { out(it, bloomed.x + bloomed.w - hd) }
        // The drop of a bar with no centre is glass of its own between back and the actions while it lasts.
        val drop = if (center1 == null && bloomed.h > .5f) bloomed else null
        val all = listOf(left) + listOfNotNull(center ?: drop) + actions

        // Gone once the capsule has shrunk away, and the pieces with it, being as high as it is.
        hidden = b < GONE
        val pieces = all.map { GlassRect(it.x, it.y, it.w, it.h, min(R, it.h / 2)) }
        val cy = top + H / 2
        // As the composer's pieces: one liquid body wherever they touch, necks that fill as the ends sink in and snap a
        // few dp thick as they part, and one capsule once they lie a radius deep in each other.
        val bridges = pieces.zipWithNext { a, b ->
            Bridge(Point(a.right - a.r, cy), a.r, Point(b.x + b.r, cy), b.r, if (oneCapsule(a, b)) 0f else join(b.x - a.right, ComposerModel.BREAK, min(a.r, b.r)))
        }.toMutableList()
        // The menu leaves its piece as the dock's leaves a cell. The piece recoils as the drop snaps off and bulges as
        // it merges back, as a hold menu's control does.
        val source = if (menuOf == CENTER) center else actions.getOrNull(menuOf)
        val shapes = pieces.toMutableList()
        var menuDraw: HoldMenuDraw? = null
        if (source == null || hidden) menu.vanish() else {
            val k = all.indexOf(source)
            val rc = recoil.x * 2.5f
            shapes[k] = GlassRect(source.x - rc, source.y - rc, source.w + 2 * rc, source.h + 2 * rc, R + rc)
            menu.frame(sec, Circle(source.x + source.w / 2, cy, R - 4), source.y, i.width, i.reduced, recoil, below = source.y + source.h)?.let { (geo, draw) ->
                shapes += geo.rect
                bridges += geo.bridge
                menuDraw = draw
            }
        }
        val glass = if (hidden) GlassOutline.Empty else GlassOutline(joined(shapes), bridges)
        // The contents show on the composer's timing, but never while two pieces are still one capsule: they fade in
        // as the deepest pair's ends come out of each other (from half a radius deep to half a radius apart, about the
        // composer's 50 ms), and out as they sink back in. Deeper than that, their full necks make them read as one body.
        // How long that takes depends on the widths (a long name parts last), so it is measured, not timed, or the
        // name would show over the call.
        val deepest = pieces.zipWithNext { a, b -> a.right - b.x }.maxOrNull()
        val parted = deepest?.let { smooth(R / 2, -R / 2, it) } ?: 1f
        val timed = if (open) smooth(.3f, .55f, t) else smooth(ComposerModel.CLOSE_OUT_TO, ComposerModel.CLOSE_OUT_FROM, t)
        val contentAlpha = if (hidden) 0f else min(timed, parted)
        // The face and name sit still while the glass blooms around them, and show once it is nearly full.
        val centerAlpha = if (center == null || hidden) 0f else smooth(NAME_FROM, NAME_TO, b)
        return TopBarFrame(glass, left, center, actions, contentAlpha, centerAlpha, menuDraw)
    }

    companion object {
        const val H = 50f
        const val R = 25f
        const val EDGE = 18f
        const val GAP = 10f
        /** The row's top, below the status bar. */
        const val TOP = 8f
        /**
         * The bloom's spring. Closing, the pieces join on a quicker spring than the composer's and the capsule shrinks
         * once they are mostly back in it, so the whole bar is gone before the composer hands over to the dock.
         */
        const val BLOOM_RESPONSE = .5f
        const val BLOOM_DAMPING = .8f
        const val JOIN_RESPONSE = .3f
        const val JOIN_DAMPING = 1f
        const val SHRINK_RESPONSE = .22f
        const val SHRINK_DAMPING = 1f
        /** How long after the bloom starts the pieces split out of it, in ms. */
        const val SPLIT_AFTER = 110f
        /** The capsule shrinks once the pieces are this far back in, or this many ms after they started back. */
        const val JOINED = .35f
        const val SHRINK_AFTER = 110f
        /** The share of the bloom by which the capsule has its full height. */
        const val FILL = .55f
        /** The bloom over which the face and name fade in. */
        const val NAME_FROM = .7f
        const val NAME_TO = .95f
        /** Below this, the bloom and the split are out of sight. */
        const val GONE = .06f
        const val WIDTH_RESPONSE = .32f
        const val WIDTH_DAMPING = .8f
        /** A text button's padding on either side of its word. */
        const val TEXT_PAD = 16f
        /** [openMenu]'s piece for the centre capsule. */
        const val CENTER = -1
    }
}

/**
 * A button on the top bar, a piece of glass of its own: an [icon] (a disc), or a word such as Done ([text], a capsule).
 * [label] is what TalkBack says. A [lit] one wears the lens, such as a star that is on. A [red] one has a red icon.
 * With a [menu], such as •••, a tap drops the menu out of it instead of running [onClick].
 */
class TopBarButton(
    val label: String, val icon: ImageVector? = null, val text: String? = null,
    val lit: Boolean = false, val red: Boolean = false, val menu: DockMenu? = null, val onClick: () -> Unit = {},
)

/** One top bar for a page's life, from the frame it opens in until it has run back out of sight. */
@Stable
class TopBarState internal constructor() {
    internal val model = TopBarModel()
    internal var frame by mutableStateOf<TopBarFrame?>(null)
    internal var wake by mutableIntStateOf(0)
    /** How far the bar reaches down from the window's top at rest, in dp, so a page can start below it. */
    var clearance by mutableFloatStateOf(0f)
        internal set
    /** True while a menu of the bar's is open. */
    var menuOpen by mutableStateOf(false)
        internal set
    // The menu whose rows show, kept while its drop merges back.
    internal var shown by mutableStateOf<DockMenu?>(null)

    /** Closes the bar's open menu; its drop merges back into its piece. */
    fun closeMenu() { model.closeMenu(); menuOpen = false; wake++ }
    internal fun openMenu(of: Int, menu: DockMenu, size: MenuSize) { model.openMenu(of, size); shown = menu; menuOpen = true; wake++ }
    internal fun pick(row: Int) { val m = shown; closeMenu(); m?.onPick?.invoke(row) }

    /** Runs the pieces back into the capsule, which shrinks away; [LiquidTopBar]'s onClosed follows once it's gone. */
    fun close() { model.open = false; menuOpen = false; wake++ }
    /** Splits the pieces again from wherever they are, for a page opened while they still run back. */
    fun open() { if (!model.open) { model.open = true; wake++ } }
}

@Composable
fun rememberTopBarState(): TopBarState = remember { TopBarState() }

/**
 * The top of a page in liquid glass: [back] as a disc on the left, a capsule in the middle holding [center] (a face and
 * a name) and the [actions] on the right, each a piece of its own (a disc for an icon, a capsule for a word), drawn
 * from the backdrop with the theme's rim, as the composer's pieces are. The capsule blooms in place and the others
 * split out of it as the composer splits out of the dock, and [TopBarState.close] runs it back in; [onClosed] follows
 * once it is out of sight. Like [LiquidComposer] it fills the window above a [backdropSource] and takes touches only on its
 * pieces, and on an open menu. [onCenter] makes the centre capsule a button, such as one opening the person, and
 * [centerMenu] makes it open a menu instead. An action's or the centre's menu is the dock's hold menu dropping
 * downward, centred under its piece; its rows act, so none is lit, and a tap anywhere else or Back closes it.
 * [center] is laid out 50 dp high and as wide as it needs, and the capsule takes its width.
 */
@Composable
fun LiquidTopBar(
    state: TopBarState,
    backdrop: Backdrop?,
    back: TopBarButton,
    modifier: Modifier = Modifier,
    actions: List<TopBarButton> = emptyList(),
    onCenter: (() -> Unit)? = null,
    centerMenu: DockMenu? = null,
    onClosed: () -> Unit = {},
    glass: GlassResources = rememberGlassResources(),
    center: (@Composable () -> Unit)? = null,
) {
    val tokens = LocalGlassTokens.current
    val view = LocalView.current
    // Not named density: inside the Canvas below, DrawScope's own density (a Float) must stay in view.
    val screen by rememberUpdatedState(LocalDensity.current)
    val reduced by rememberUpdatedState(prefersReducedMotion())
    val status = WindowInsets.statusBars
    val measurer = rememberTextMeasurer()
    val latestOnClosed by rememberUpdatedState(onClosed)
    var size by remember { mutableStateOf(Size.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var contentW by remember { mutableFloatStateOf(0f) }
    val textStyle = TextStyle(color = tokens.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    fun cellW(b: TopBarButton) = b.text?.let { measurer.measure(it, textStyle).size.width / screen.density + 2 * TopBarModel.TEXT_PAD } ?: TopBarModel.H
    // Read by the frame loop, which outlives this composition: a word that changes (Edit to Done) must reach it.
    val leftW by rememberUpdatedState(cellW(back))
    val cells by rememberUpdatedState(actions.map(::cellW))
    val hasCenter by rememberUpdatedState(center != null)

    fun inputs(): TopBarInputs {
        val d = screen.density
        return TopBarInputs(
            width = size.width / d, safeTop = status.getTop(screen) / d, reduced = reduced, leftW = leftW,
            centerW = if (hasCenter) contentW else null, actionsW = cells, period = frameMs(view),
        )
    }
    // A tap on a piece with a menu opens it, or closes it when it is the one open, with the confirm pulse.
    fun toggle(of: Int, menu: DockMenu) {
        if (state.menuOpen && state.model.menuOf == of) { state.closeMenu(); return }
        state.openMenu(of, menu, menuSize(menu, measurer, screen.density))
        Haptics.perform(view, Haptic.Confirm)
    }
    var closedSent by remember { mutableStateOf(false) }
    fun step(dt: Float) {
        val i = inputs()
        state.frame = state.model.frame(dt, i)
        state.clearance = i.safeTop + TopBarModel.TOP + TopBarModel.H
        if (state.menuOpen && !state.model.menuOpen) state.menuOpen = false
        // onClosed follows each close once; a bar that was split again on the way back closes afresh.
        if (state.model.open) closedSent = false
        else if (state.model.closed && !closedSent) { closedSent = true; latestOnClosed() }
    }

    // The frame loop runs only while something moves; at rest it waits for a turn or for the contents to change.
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

    Box(modifier.fillMaxSize().onSizeChanged {
        size = Size(it.width.toFloat(), it.height.toFloat())
        if (state.frame == null) step(0f)
    }.onPlaced { origin = it.positionInRoot() }) {
        Canvas(Modifier.fillMaxSize()) {
            val f = state.frame ?: return@Canvas
            drawLiquidGlass(f.glass, backdrop, glass, tokens, origin)
            val a = f.contentAlpha
            if (a <= .001f) return@Canvas
            fun cell(b: TopBarButton, box: Box) {
                if (b.lit) drawLens(LensDraw(box.x + 4, box.y + 4, box.w - 8, box.h - 8, 1f, 1f, a), tokens, radius = 21f)
                val cx = box.x + box.w / 2
                val cy = box.y + box.h / 2
                if (b.text != null) {
                    val layout = measurer.measure(b.text, textStyle)
                    val topLeft = Offset(cx * density - layout.size.width / 2f, cy * density - layout.size.height / 2f)
                    for (s in tokens.textShadow) drawText(layout, color = tokens.text.copy(alpha = a), topLeft = topLeft, shadow = s.scaled(density))
                } else if (b.icon != null) {
                    drawLineIcon(b.icon, cx, cy, 1f, (if (b.red) tokens.red else tokens.onGlass).copy(alpha = a), size = 22f)
                }
            }
            cell(back, f.left)
            actions.forEachIndexed { i, b -> f.actions.getOrNull(i)?.let { cell(b, it) } }
        }
        if (center != null) {
            val shown by remember(state) { derivedStateOf { (state.frame?.centerAlpha ?: 0f) > .5f } }
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .placeAt { state.frame?.center }
                    .graphicsLayer {
                        alpha = state.frame?.centerAlpha ?: 0f
                        clip = true
                        shape = RoundedCornerShape(50)
                    }
                    .then(
                        when {
                            !shown -> Modifier
                            centerMenu != null -> Modifier.clickable(press, null, role = Role.Button) { toggle(TopBarModel.CENTER, centerMenu) }
                            onCenter != null -> Modifier.clickable(press, null, role = Role.Button) { onCenter() }
                            else -> Modifier
                        },
                    ),
            ) {
                // Measured as wide as it needs, up to the room between the discs, and centred in the capsule as it is
                // now, so its contents never move inside the glass while the capsule widens. The box is the capsule's
                // 50 dp high and centres the contents in it: a Box gives its child no minimum height and would leave
                // them at its top.
                Box(Modifier.layout { m, c ->
                    val room = max(0f, size.width / density - 2 * TopBarModel.EDGE - leftW - cells.sum() - TopBarModel.GAP * (cells.size + 1))
                    val h = (TopBarModel.H * density).roundToInt()
                    val p = m.measure(Constraints(maxWidth = (room * density).roundToInt(), minHeight = h, maxHeight = h))
                    contentW = p.width / density
                    layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
                }, contentAlignment = Alignment.Center) { center() }
            }
        }
        BarTap(state, { it.left }, back.label, back.onClick)
        actions.forEachIndexed { i, b -> BarTap(state, { f -> f.actions.getOrNull(i) }, b.label) { b.menu?.let { toggle(i, it) } ?: b.onClick() } }
        val rows by remember(state) { derivedStateOf { state.shown?.takeIf { state.frame?.menu != null } } }
        rows?.let { m -> MenuRows(m.options, tokens) { state.frame?.menu } }
        if (state.menuOpen) TopBarMenuTouch(state) { origin }
    }
    BackHandler(enabled = state.menuOpen) { state.closeMenu() }
}

/**
 * An open menu takes every touch: a tap on a row picks it, a tap anywhere else closes it, as a hold menu opened by a tap
 * does. Each row is also a button for TalkBack.
 */
@Composable
private fun TopBarMenuTouch(state: TopBarState, origin: () -> Offset) {
    Box(Modifier.fillMaxSize().pointerInput(state) {
        detectTapGestures { at ->
            val o = origin()
            state.model.rowAt((at.x + o.x) / density, (at.y + o.y) / density)?.let(state::pick) ?: state.closeMenu()
        }
    })
    val options = state.shown?.options ?: return
    options.forEachIndexed { i, o ->
        Box(
            Modifier
                .placeAt {
                    state.frame?.menu?.final?.let { f -> Box(f.x + DockModel.MENU_PAD, f.y + DockModel.MENU_PAD + i * (DockModel.ROW + DockModel.ROW_GAP), f.w - 2 * DockModel.MENU_PAD, DockModel.ROW) }
                }
                .semantics {
                    contentDescription = o.label
                    role = Role.Button
                    onClick { state.pick(i); true }
                },
        )
    }
}

/** A button's tap target while its content shows. Not composed otherwise, since a clickable at 0 x 0 still takes touches. */
@Composable
private fun BarTap(state: TopBarState, cell: (TopBarFrame) -> Box?, label: String, onClick: () -> Unit) {
    val shown by remember(state) { derivedStateOf { (state.frame?.contentAlpha ?: 0f) > .5f } }
    if (!shown) return
    Box(
        Modifier
            .placeAt { state.frame?.takeIf { it.contentAlpha > .5f }?.let(cell) }
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
    )
}
