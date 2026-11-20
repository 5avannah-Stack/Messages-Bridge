package cz.teous.liquid

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** One action behind a sliding row: a panel in the tray that a slide to the left uncovers. */
class SlideAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * The slide's arithmetic, apart from Compose so it runs in unit tests. [offset] is the row's shift in px, to the right
 * (delete) positive and to the left (the actions) negative.
 */
object SlideModel {
    /** Each action's panel, in dp. */
    const val PANEL_DP = 76f

    /** Past this share of the row's width, letting go deletes. */
    const val FULL = .4f

    /** An ordinary flick, in dp/s, opens the actions or deletes, once the row has moved at least [FLICK_MIN_DP]. */
    const val FLICK_DP = 600f
    const val FLICK_MIN_DP = 16f

    enum class End { Delete, Open, Close }

    /** 0 at rest, 1 where letting go deletes. */
    fun doom(offset: Float, width: Float): Float = clamp(offset / (width * FULL), 0f, 1f)

    /** Whether letting go here deletes. */
    fun past(offset: Float, width: Float): Boolean = offset > width * FULL

    /** The finger pulls the actions a little past their panels and the row all the way out to delete. */
    fun bound(offset: Float, reveal: Float, width: Float): Float = clamp(offset, if (reveal > 0f) -reveal * 1.25f else 0f, width)

    /** What the row does when the finger lifts at [offset] moving at [velocity] px/s. */
    fun release(offset: Float, velocity: Float, reveal: Float, width: Float, flick: Float, flickMin: Float): End = when {
        past(offset, width) || (offset > flickMin && velocity > flick) -> End.Delete
        offset >= 0f -> End.Close
        opens(offset, reveal) || (reveal > 0f && -offset > flickMin && velocity < -flick) -> End.Open
        else -> End.Close
    }

    /** Whether letting go here opens the actions: past half their panels. */
    fun opens(offset: Float, reveal: Float): Boolean = reveal > 0f && -offset > reveal / 2

    /** How far the delete tray's rim has turned red: a little on the way, all of it once letting go deletes. */
    fun red(doom: Float, armed: Float): Float = clamp(max(.35f * doom, armed), 0f, 1f)

    /** The trash's centre in a delete tray [w] wide: centred while it is narrow, then [ride] before the row's edge. */
    fun trashAt(w: Float, ride: Float): Float = max(w / 2, w - ride)

    /** Keeps the finger's speed into a spring only when it points where the spring goes, so nothing runs past. */
    fun carry(velocity: Float, from: Float, to: Float): Float = if ((to - from) * velocity > 0f) velocity else 0f
}

/** The rows of one list that share it keep one open at a time: sliding one closes any other that is open. */
@Stable
class SlideGroup {
    internal var open by mutableStateOf<Any?>(null)
}

/** A [SlideGroup] for one list, kept across recompositions. */
@Composable
fun rememberSlideGroup(): SlideGroup = remember { SlideGroup() }

/**
 * A list row that slides, the same in every app: to the left over its actions, to the right to delete.
 * Whatever the row uncovers is one tray of glass, lit by the theme's rim: equal lines along the top and bottom and a
 * faint ring. Each action gets an equal panel of [SlideModel.PANEL_DP], split by short lines.
 * To the right the tray runs from the screen's edge to the row's with the trash riding before the row; its rim turns
 * red as the slide nears delete, and past [SlideModel.FULL] the tray takes a little red, the trash swells and a confirm
 * plays, so the finger knows letting go deletes. The row casts a soft shadow onto the tray and dims as it heads for
 * delete. Tapping an action closes the row, then acts; so does a tap on the open row itself, which doesn't reach the
 * row's content. A tick marks the point where letting go opens the actions, both ways. Rows given one [group] keep one open at a time. TalkBack gets every action and Delete as custom actions.
 *
 * [onDelete] runs once the row has left. Remove the item there; Undo brings it back as a new row. A row that stays
 * composed stays out.
 *
 * Only offsets and draw blocks read the slide, so sliding redraws without recomposing. The row's [content] must be
 * opaque; the tray draws over whatever is behind this, such as a card's fill.
 */
@Composable
fun LiquidSlideRow(
    actions: List<SlideAction>,
    deleteIcon: ImageVector,
    deleteLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    group: SlideGroup? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val current by rememberUpdatedState(actions)
    val delete by rememberUpdatedState(onDelete)
    var x by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var past by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<Job?>(null) }
    val armed by animateFloatAsState(if (past) 1f else 0f, spring(dampingRatio = .7f, stiffness = 900f), label = "armed")
    val panel = with(density) { SlideModel.PANEL_DP.dp.toPx() }
    val reveal = panel * actions.size
    val flick = with(density) { SlideModel.FLICK_DP.dp.toPx() }
    val flickMin = with(density) { SlideModel.FLICK_MIN_DP.dp.toPx() }

    fun glide(to: Float, velocity: Float, spec: AnimationSpec<Float>, then: () -> Unit = {}) {
        motion?.cancel()
        motion = scope.launch {
            animate(x, to, SlideModel.carry(velocity, x, to), spec) { value, _ -> x = value }
            then()
        }
    }
    // Critically damped, so the row lands on its panels or back in place without running past.
    fun settle(to: Float, velocity: Float = 0f) = glide(to, velocity, spring(dampingRatio = 1f, stiffness = 500f))
    fun remove(velocity: Float = 0f) {
        if (!past) Haptics.perform(view, Haptic.Confirm)
        past = true
        // Ends within a pixel rather than a hundredth of one, so what follows isn't held back by an invisible tail.
        glide(width.toFloat(), velocity, spring(stiffness = 700f, visibilityThreshold = 1f)) { delete() }
    }
    fun act(action: SlideAction) {
        settle(0f)
        action.onClick()
    }

    // Another row of the group starting to slide closes this one, unless it is already on its way out.
    val me = remember { Any() }
    LaunchedEffect(group) {
        group ?: return@LaunchedEffect
        snapshotFlow { group.open }.collect { if (it != null && it !== me && x != 0f && !past) settle(0f) }
    }
    // Changes only as the row leaves or reaches rest, so the slide itself still never recomposes.
    val open by remember { derivedStateOf { x != 0f } }

    val tracker = remember { VelocityTracker() }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = current.map { CustomAccessibilityAction(it.label) { act(it); true } } +
                    CustomAccessibilityAction(deleteLabel) { remove(); true }
            }
            .drawBehind { drawTrays(tokens, x, current, deleteIcon, armed) }
            .pointerInput(Unit) {
                // A tap on the tray: an action's panel, or the delete tray.
                detectTapGestures { at ->
                    val w = size.width.toFloat()
                    when {
                        x > 0f && at.x < x -> remove()
                        x < 0f && at.x > w + x -> {
                            val list = current
                            val i = ((at.x - (w + x)) / (-x / list.size)).toInt().coerceIn(0, list.lastIndex)
                            act(list[i])
                        }
                    }
                }
            }
            .pointerInput(reveal) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        group?.open = me
                        opening = SlideModel.opens(x, reveal)
                        motion?.cancel()
                        tracker.resetTracking()
                        travel = 0f
                    },
                    onDragEnd = {
                        val w = width.toFloat()
                        val v = tracker.calculateVelocity().x
                        when (SlideModel.release(x, v, reveal, w, flick, flickMin)) {
                            SlideModel.End.Delete -> remove(v)
                            SlideModel.End.Open -> settle(-reveal, v)
                            SlideModel.End.Close -> settle(0f, v)
                        }
                    },
                    onDragCancel = {
                        past = false
                        settle(0f)
                    },
                ) { change, drag ->
                    change.consume()
                    // The finger's own travel, since the row moves under it and its place within the row barely changes.
                    travel += drag
                    tracker.addPosition(change.uptimeMillis, Offset(travel, 0f))
                    x = SlideModel.bound(x + drag, reveal, width.toFloat())
                    val now = SlideModel.past(x, width.toFloat())
                    if (now != past) {
                        past = now
                        Haptics.perform(view, if (now) Haptic.Confirm else Haptic.Tick)
                    }
                    // A tick where letting go would open the actions, and again where it would close them.
                    val opens = SlideModel.opens(x, reveal)
                    if (opens != opening) {
                        opening = opens
                        Haptics.perform(view, Haptic.Tick)
                    }
                }
            },
    ) {
        Box(
            Modifier.graphicsLayer {
                translationX = x
                alpha = 1f - .35f * SlideModel.doom(x, width.toFloat())
            },
        ) {
            content()
            // While the row is open, a tap on it closes it instead of reaching the content (opening a chat).
            if (open) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { if (!past) settle(0f) } })
        }
    }
}

/** Both trays, from the row's shift [x]: the actions' to the right of the row, delete's to its left. */
private fun DrawScope.drawTrays(tokens: GlassTokens, x: Float, actions: List<SlideAction>, deleteIcon: ImageVector, armed: Float) {
    val d = density
    val w = size.width
    val h = size.height
    if (x < 0f && actions.isNotEmpty()) {
        val u = -x
        val left = w - u
        drawTray(tokens, left, u, 0f)
        val each = u / actions.size
        // Short lines between the panels, clear of the tray's top and bottom.
        for (i in 1 until actions.size) {
            drawRect(tokens.rim.copy(alpha = .1f), Offset(left + each * i, 16f * d), Size(d, h - 32f * d))
        }
        val shown = clamp((each - 8f * d) / (31f * d), 0f, 1f)
        actions.forEachIndexed { i, action ->
            drawLineIcon(action.icon, (left + each * (i + .5f)) / d, h / 2 / d, 1f, tokens.text3.copy(alpha = tokens.text3.alpha * shown), ICON)
        }
        drawShadow(left, 18f * d, toRight = true, lift = min(1f, u / (24f * d)))
    } else if (x > 0f) {
        val doom = SlideModel.doom(x, w)
        val red = SlideModel.red(doom, armed)
        drawTray(tokens, 0f, x, red)
        // The tray takes a little red once letting go would delete.
        drawRect(tokens.red.copy(alpha = max(.04f * doom, .14f * armed)), Offset.Zero, Size(x, h))
        val cx = SlideModel.trashAt(x, 24f * d + ICON / 2 * d)
        drawLineIcon(deleteIcon, cx / d, h / 2 / d, 1f + .08f * armed, tokens.red.copy(alpha = min(1f, x / (44f * d))), ICON)
        drawShadow(x, 18f * d, toRight = false, lift = min(1f, x / (24f * d)))
    }
}

/**
 * One tray of glass, [left] to [left] + [w], lit by the theme's rim: a faint ring and a line along the top and the
 * bottom, the two equally strong (T, 2026-11-17: a stronger top line read as a heavier edge). [red] turns that light
 * red (the same lines, not one more), for delete.
 */
private fun DrawScope.drawTray(tokens: GlassTokens, left: Float, w: Float, red: Float) {
    if (w <= 0f) return
    val d = density
    val h = size.height
    val rim = lerp(tokens.rim, tokens.red, red)
    drawRect(tokens.lens, Offset(left, 0f), Size(w, h))
    drawRect(rim.copy(alpha = tokens.rimBase), Offset(left + d / 2, d / 2), Size(w - d, h - d), style = Stroke(d))
    val edge = rim.copy(alpha = (tokens.rimTop + tokens.rimBottom) / 2 + .1f * red)
    drawRect(edge, Offset(left, 0f), Size(w, d))
    drawRect(edge, Offset(left, h - d), Size(w, d))
}

/** The row's soft shadow on the tray beside its edge at [edge], [reach] px across. */
private fun DrawScope.drawShadow(edge: Float, reach: Float, toRight: Boolean, lift: Float) {
    if (lift <= 0f) return
    val near = Color.Black.copy(alpha = .35f * lift)
    val start = if (toRight) edge else edge - reach
    val brush = Brush.horizontalGradient(if (toRight) listOf(near, Color.Transparent) else listOf(Color.Transparent, near), start, start + reach)
    drawRect(brush, Offset(start, 0f), Size(reach, size.height))
}

/** The panels' icons, in dp. */
private const val ICON = 21f
