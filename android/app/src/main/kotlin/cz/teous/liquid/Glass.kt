package cz.teous.liquid

import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How the backdrop is drawn on this phone. */
enum class GlassLevel {
    /** API 33+: blurred and bent at the rim, worked out in an AGSL shader from the shapes themselves. */
    Bent,
    /** API 31 and 32: blurred, saturated and darkened, with no bend. */
    Blurred,
    /** API 29 and 30: no RenderEffect, so the glass is an opaque fill under its rim. */
    Opaque;

    companion object {
        fun of(refraction: Boolean) = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && refraction -> Bent
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Blurred
            else -> Opaque
        }
    }
}

/** The layers and shaders one piece of glass draws with, kept for its life so a frame allocates no shader. */
class GlassResources internal constructor(
    internal val backdropLayer: GraphicsLayer, internal val bandLayer: GraphicsLayer,
    internal val glowLayer: GraphicsLayer, internal val maskLayer: GraphicsLayer,
) {
    // Typed Any so the class loads on API 29 to 32, where RuntimeShader doesn't exist.
    internal val bend: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Shaders.bend() else null
    internal val finish: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Shaders.finish() else null
    /** Counts the times the glass was drawn, so checks can prove that glass at rest isn't redrawn. */
    var draws = 0
        internal set
    /** How the backdrop was drawn last time. */
    var level: GlassLevel? = null
        internal set
}

@Composable
fun rememberGlassResources(): GlassResources {
    val a = rememberGraphicsLayer()
    val b = rememberGraphicsLayer()
    val c = rememberGraphicsLayer()
    val m = rememberGraphicsLayer()
    return remember(a, b, c, m) { GlassResources(a, b, c, m) }
}

/** CSS blur lengths are a standard deviation; Android's blur takes a radius, with sigma = 0.57735 r + 0.5. */
internal fun sigmaToRadius(sigma: Float) = max(.01f, (sigma - .5f) / .57735f)

private fun GlassRect.path(d: Float) = Path().apply { addRoundRect(RoundRect(x * d, y * d, right * d, bottom * d, CornerRadius(radius * d))) }

private fun BridgeCurve.path(d: Float) = Path().apply {
    moveTo(p1.x * d, p1.y * d)
    cubicTo(h1.x * d, h1.y * d, h3.x * d, h3.y * d, p3.x * d, p3.y * d)
    lineTo(p4.x * d, p4.y * d)
    cubicTo(h4.x * d, h4.y * d, h2.x * d, h2.y * d, p2.x * d, p2.y * d)
    close()
}

/** The outline of every shape and bridge as one path, in px. */
fun GlassOutline.path(density: Float): Path {
    var acc: Path? = null
    fun add(p: Path) { acc = acc?.let { Path.combine(PathOperation.Union, it, p) } ?: p }
    for (s in shapes) add(s.path(density))
    for (b in bridges) bridgeCurve(b)?.let { add(it.path(density)) }
    return acc ?: Path()
}

/** A bridge's two curves on their own, without the straight ends that lie inside the pieces it joins. */
private fun BridgeCurve.edges(d: Float) = Path().apply {
    moveTo(p1.x * d, p1.y * d)
    cubicTo(h1.x * d, h1.y * d, h3.x * d, h3.y * d, p3.x * d, p3.y * d)
    moveTo(p4.x * d, p4.y * d)
    cubicTo(h4.x * d, h4.y * d, h2.x * d, h2.y * d, p2.x * d, p2.y * d)
}

/** The part of a rounded rect that lies deeper than the rim's band ([band] dp), or null when it has none. */
private fun GlassRect.core(d: Float, band: Float): Path? =
    if (w <= 2 * band || h <= 2 * band) null else GlassRect(x + band, y + band, w - 2 * band, h - 2 * band, radius - band).path(d)

/** Runs [body] with every path in [paths] from [k] on clipped out. */
private fun DrawScope.clipOut(paths: List<Path>, k: Int, body: DrawScope.() -> Unit) {
    if (k >= paths.size) body() else clipPath(paths[k], ClipOp.Difference) { clipOut(paths, k + 1, body) }
}

/**
 * Draws one piece of liquid glass (liquid.js `Glass.draw`): the backdrop bent and blurred inside the outline, the mist
 * fill, and the rim worked out from the filled outline only, so no edge shows where pieces join. [origin] is where
 * this draw scope sits in the window, in px, so the backdrop lines up with what is really behind it. Draw it in a
 * scope that keeps its size while the glass moves, such as the whole window.
 */
fun DrawScope.drawLiquidGlass(
    outline: GlassOutline, backdrop: Backdrop?, res: GlassResources, tokens: GlassTokens, origin: Offset = Offset.Zero,
) {
    if (!outline.visible) return
    res.draws++
    val level = if (backdrop == null) GlassLevel.Opaque else GlassLevel.of(tokens.refraction)
    res.level = level
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || backdrop == null) drawPlainGlass(outline, tokens)
    else drawPieceGlass(outline, backdrop, res, tokens, origin, level)
}

/** The shapes' bounds with room for the rim's softening, in px. */
private fun DrawScope.bounds(outline: GlassOutline): Rect {
    val d = density
    return Rect(
        floor(outline.shapes.minOf { it.x } * d - 4 * d), floor(outline.shapes.minOf { it.y } * d - 4 * d),
        ceil(outline.shapes.maxOf { it.right } * d + 4 * d), ceil(outline.shapes.maxOf { it.bottom } * d + 4 * d),
    )
}

/**
 * The edges of the glass's passes sit on this grid, in px. A blur works on a smaller copy of its pass, so the grid is
 * a power of two.
 */
private const val GRID = 256f

/**
 * The band of the scope the glass is worked out in, in px: its full width, from 32 dp above the outline to 32 dp below
 * it, with both edges on [GRID] and cut to the scope. The bend reaches 19 dp past an edge and the blur under it 4 dp
 * further, and the blur without the bend 27 dp, so within 32 dp the glass gets every pixel it would get from the whole
 * window. A dock at the bottom of a phone needs about a fifth of the window's rows.
 */
private fun DrawScope.passBox(outline: GlassOutline): Rect {
    val reach = 32 * density
    val top = floor((outline.shapes.minOf { it.y } * density - reach) / GRID) * GRID
    val bottom = ceil((outline.shapes.maxOf { it.bottom } * density + reach) / GRID) * GRID
    return Rect(0f, top.coerceIn(0f, size.height), size.width, bottom.coerceIn(0f, size.height))
}

/**
 * API 31 and up. A piece of glass draws the same pixels whatever else shares its outline: a menu opening above the
 * dock doesn't change the dock. So nothing here works on the merged outline: a merged path rasterises differently from
 * a lone rounded rect. Coverage is a mask of the pieces drawn one by one, and the rim's band is stroked per piece and
 * cut where it lies deep inside another, so pieces that touch are edged as one body and no edge shows where they join.
 *
 * With the bend, every layer and offscreen pass covers [passBox] and no more. Each one is filled, blurred and blended
 * over all of its pixels in every frame the glass moves, so passes the size of the window make each frame pay for rows
 * the glass is nowhere near. Skia works a blur out within the pass it is drawn into, so the box never follows the outline itself:
 * it is as wide as the scope, and it grows up or down a whole [GRID] step at a time.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun DrawScope.drawPieceGlass(
    outline: GlassOutline, backdrop: Backdrop, res: GlassResources, tokens: GlassTokens, origin: Offset, level: GlassLevel,
) {
    val d = density
    val canvas = drawContext.canvas
    // The blur alone (API 31 and 32) still changed the dock by about half a level of 255 when an opening menu grew the
    // band (GlassFramesTest, on Teodor-PC's emulator), so it keeps passes the size of the window.
    val box = if (level == GlassLevel.Bent) passBox(outline) else Rect(0f, 0f, size.width, size.height)
    if (box.height < 1f || box.width < 1f) return
    val boxAt = IntOffset(0, box.top.toInt())
    val boxSize = IntSize(box.width.toInt(), box.height.toInt())
    val rects = outline.shapes.map { it.path(d) }
    val cores = outline.shapes.map { it.core(d, tokens.rimWidth) }
    val curves = outline.bridges.mapNotNull { bridgeCurve(it) }
    val necks = curves.map { it.path(d) }
    fun DrawScope.fillPieces() { for (p in rects) drawPath(p, Color.White); for (p in necks) drawPath(p, Color.White) }
    // A layer holds the box only and sits at the box's corner, so what is drawn into it moves up by the box's top.
    fun DrawScope.inBox(body: DrawScope.() -> Unit) { translate(0f, -box.top) { body() } }
    fun pass(paint: Paint = Paint(), body: () -> Unit) { canvas.saveLayer(box, paint); body(); canvas.restore() }
    val keepIn = Paint().apply { blendMode = BlendMode.DstIn }

    val mask = res.maskLayer
    mask.topLeft = boxAt
    mask.record(boxSize) { inBox { fillPieces() } }

    // The backdrop, bent or blurred, kept inside the mask; then the mist over it.
    val layer = res.backdropLayer
    // Bent is only picked on API 33+ (GlassLevel), checked again here so lint can see it.
    layer.renderEffect = if (level == GlassLevel.Bent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) bentEffect(res, outline, box, d)
    else blurredEffect(res, tokens, d)
    layer.topLeft = boxAt
    layer.record(boxSize) { translate(backdrop.origin.x - origin.x, backdrop.origin.y - origin.y - box.top) { drawLayer(backdrop.layer) } }
    pass {
        drawLayer(layer)
        pass(keepIn) { drawLayer(mask) }
    }
    pass(Paint().apply { colorFilter = ColorFilter.tint(tokens.fill) }) { drawLayer(mask) }

    // The rim's band: the rimWidth dp just inside the body's outline, softened by rimSoft dp. Each piece's own band is cut only
    // where it lies deeper than that inside another piece, so the band follows the body: it runs on along an edge two
    // pieces share and round the joins, and shows nowhere inside. A neck is edged along its two curves only.
    val band = res.bandLayer
    band.renderEffect = android.graphics.RenderEffect
        .createBlurEffect(sigmaToRadius(tokens.rimSoft * d), sigmaToRadius(tokens.rimSoft * d), Shader.TileMode.DECAL).asComposeRenderEffect()
    band.topLeft = boxAt
    band.record(boxSize) {
        inBox {
            rects.forEachIndexed { i, p ->
                clipPath(p) { clipOut(cores.filterIndexed { j, _ -> j != i }.filterNotNull() + necks, 0) { drawPath(p, Color.White, style = Stroke(2 * tokens.rimWidth * d)) } }
            }
            val deep = cores.filterNotNull()
            curves.forEachIndexed { i, c ->
                clipPath(necks[i]) { clipOut(deep, 0) { drawPath(c.edges(d), Color.White, style = Stroke(2 * tokens.rimWidth * d)) } }
            }
        }
    }
    pass {
        drawRimLight(outline, box, d, tokens)
        pass(keepIn) { drawLayer(band) }
        pass(keepIn) { drawLayer(mask) }
    }

    // Inner glow: inside the outline, the rim's colour at glow times (1 - the outline blurred by glowWidth dp).
    val glow = res.glowLayer
    glow.renderEffect = android.graphics.RenderEffect
        .createBlurEffect(sigmaToRadius(tokens.glowWidth * d), sigmaToRadius(tokens.glowWidth * d), Shader.TileMode.DECAL).asComposeRenderEffect()
    glow.topLeft = boxAt
    glow.record(boxSize) { inBox { fillPieces() } }
    pass {
        pass(Paint().apply { colorFilter = ColorFilter.tint(tokens.rim.copy(alpha = tokens.glow)) }) { drawLayer(mask) }
        pass(Paint().apply { blendMode = BlendMode.DstOut }) { drawLayer(glow) }
    }
}

/**
 * The light the rim's band is cut from: a base over everything, and over each piece a gradient across its own
 * height (the rim tokens), so a drop leaving a capsule never dims it.
 * Pieces of one height that overlap side by side, or are joined by a neck, share one gradient ([lightBoxes]), or their
 * overlap would be lit twice and the neck not at all.
 */
private fun DrawScope.drawRimLight(outline: GlassOutline, box: Rect, d: Float, tokens: GlassTokens) {
    val rim = tokens.rim
    drawRect(rim.copy(alpha = tokens.rimBase), box.topLeft, box.size)
    // 3 dp past the ends, as the web's MARGIN: the rim's softened edge reaches about that far out.
    for (s in padBoxes(lightBoxes(outline.shapes, outline.bridges), 3f)) {
        val brush = Brush.verticalGradient(
            0f to rim.copy(alpha = tokens.rimTop), .35f to rim.copy(alpha = tokens.rimUpper),
            .75f to rim.copy(alpha = tokens.rimLower), 1f to rim.copy(alpha = tokens.rimBottom),
            startY = s.y * d, endY = (s.y + s.h) * d,
        )
        drawRect(brush, Offset(s.x * d, s.y * d), Size(s.w * d, s.h * d))
    }
}

/**
 * API 29 and 30: no RenderEffect, so the glass is an opaque fill (phone-apps design.md, "Opaque fallback") under the
 * same rim, worked out on the merged outline with mask filters. The band is blurred as drawn, centred on the outline,
 * so its light sits 1.25 dp further out than the web's.
 */
private fun DrawScope.drawPlainGlass(outline: GlassOutline, tokens: GlassTokens) {
    val d = density
    val canvas = drawContext.canvas
    val path = outline.path(d)
    val box = bounds(outline)
    drawPath(path, tokens.opaque)
    canvas.saveLayer(box, Paint())
    clipPath(path) {
        drawRimLight(outline, box, d, tokens)
        canvas.saveLayer(box, Paint().apply { blendMode = BlendMode.DstIn })
        drawIntoCanvas {
            it.nativeCanvas.drawPath(path.asAndroidPath(), android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE; strokeWidth = tokens.rimWidth * d
                maskFilter = BlurMaskFilter(sigmaToRadius(tokens.rimSoft * d), BlurMaskFilter.Blur.NORMAL)
            })
        }
        canvas.restore()
    }
    canvas.restore()
    canvas.saveLayer(box, Paint())
    drawPath(path, tokens.rim.copy(alpha = tokens.glow))
    drawIntoCanvas {
        it.nativeCanvas.drawPath(path.asAndroidPath(), android.graphics.Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(sigmaToRadius(tokens.glowWidth * d), BlurMaskFilter.Blur.INNER)
            blendMode = android.graphics.BlendMode.DST_OUT
        })
    }
    canvas.restore()
}

@RequiresApi(Build.VERSION_CODES.S)
private fun blurredEffect(res: GlassResources, tokens: GlassTokens, d: Float): androidx.compose.ui.graphics.RenderEffect {
    val r = sigmaToRadius(tokens.blur * d)
    val matrix = ColorMatrix().apply {
        setSaturation(tokens.saturation)
        postConcat(ColorMatrix().apply { setScale(tokens.brightness, tokens.brightness, tokens.brightness, 1f) })
    }
    val blur = android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
    return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), blur).asComposeRenderEffect()
}

/**
 * liquid.js's BEND filter: blur 1.2, displace by the slope of the shape's softened edge, keep what is inside the
 * shape, blur 9 within the shape only, saturate 1.6 and darken. The shapes go in as numbers; no image is made.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun bentEffect(res: GlassResources, outline: GlassOutline, box: Rect, d: Float): androidx.compose.ui.graphics.RenderEffect {
    val bend = res.bend as RuntimeShader
    val finish = res.finish as RuntimeShader
    val rects = FloatArray(Shaders.MAX * 4)
    val radii = FloatArray(Shaders.MAX)
    for (i in 0 until Shaders.MAX) {
        val s = outline.shapes.getOrNull(i)
        if (s == null) {
            // Far away and tiny: adds nothing to the edge or the coverage.
            rects[i * 4] = -1e5f; rects[i * 4 + 1] = -1e5f; rects[i * 4 + 2] = -1e5f + 1; rects[i * 4 + 3] = -1e5f + 1
        } else {
            rects[i * 4] = s.x * d - box.left; rects[i * 4 + 1] = s.y * d - box.top
            rects[i * 4 + 2] = s.right * d - box.left; rects[i * 4 + 3] = s.bottom * d - box.top
            radii[i] = s.radius * d
        }
    }
    bend.setFloatUniform("rects", rects)
    bend.setFloatUniform("radii", radii)
    bend.setFloatUniform("sigma", 8.83f * d)
    bend.setFloatUniform("shift", 3f * d)
    bend.setFloatUniform("gain", 1.88f)
    bend.setFloatUniform("strength", 38f * d)
    finish.setFloatUniform("sat", 1.6f)
    val pre = android.graphics.RenderEffect.createBlurEffect(sigmaToRadius(1.2f * d), sigmaToRadius(1.2f * d), Shader.TileMode.CLAMP)
    val bent = android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(bend, "content"), pre)
    val soft = android.graphics.RenderEffect.createBlurEffect(sigmaToRadius(9f * d), sigmaToRadius(9f * d), bent, Shader.TileMode.DECAL)
    return android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(finish, "content"), soft)
        .asComposeRenderEffect()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Shaders {
    const val MAX = 8

    // The softened edge is each rounded rect's Gaussian-blurred coverage, taken from its signed distance (exact along
    // straight edges), and the pieces combine as a union. Its slope over ±3 dp, times 1.88, gives the map; 38 dp is the
    // web's displacement scale, so a straight edge bends by up to 19 dp, as in Chromium.
    fun bend() = RuntimeShader(
        """
        uniform shader content;
        uniform float4 rects[$MAX];
        uniform float radii[$MAX];
        uniform float sigma;
        uniform float shift;
        uniform float gain;
        uniform float strength;

        float box(float2 p, float4 r, float rad) {
            float2 c = (r.xy + r.zw) * 0.5;
            float2 h = (r.zw - r.xy) * 0.5;
            float2 q = abs(p - c) - h + rad;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
        }
        // The normal distribution's CDF, logistic approximation.
        float phi(float x) {
            x = clamp(x, -12.0, 12.0);
            return 1.0 / (1.0 + exp(-1.5976 * x - 0.070566 * x * x * x));
        }
        float soft(float2 p) {
            float keep = 1.0;
            for (int i = 0; i < $MAX; i++) keep *= 1.0 - phi(-box(p, rects[i], radii[i]) / sigma);
            return 1.0 - keep;
        }
        half4 main(float2 p) {
            // Only what is inside the shapes is kept, so outside them the bend's maths is skipped. The skip holds only
            // arithmetic: the Android emulator exits on an early return, or on sampling inside the branch.
            float d = 1e9;
            for (int i = 0; i < $MAX; i++) d = min(d, box(p, rects[i], radii[i]));
            float a = clamp(0.5 - d, 0.0, 1.0);
            float2 at = p;
            if (a > 0.0) {
                float2 dx = float2(shift, 0.0);
                float2 dy = float2(0.0, shift);
                float2 slope = float2(soft(p + dx) - soft(p - dx), soft(p + dy) - soft(p - dy)) * gain;
                at = p + clamp(slope, -0.5, 0.5) * strength;
            }
            return content.eval(at) * half(a);
        }
        """.trimIndent(),
    )

    // After the blur: undo the premultiplied edge (the web's alpha table), saturate 1.6 as feColorMatrix does, and the
    // web's brightness table (0, .2125, .425, .6375, .76).
    fun finish() = RuntimeShader(
        """
        uniform shader content;
        uniform float sat;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            if (c.a < 0.0001) return half4(0.0);
            float3 v = float3(c.rgb) / float(c.a);
            float s = sat;
            float3 o = float3(
                (0.213 + 0.787 * s) * v.r + (0.715 - 0.715 * s) * v.g + (0.072 - 0.072 * s) * v.b,
                (0.213 - 0.213 * s) * v.r + (0.715 + 0.285 * s) * v.g + (0.072 - 0.072 * s) * v.b,
                (0.213 - 0.213 * s) * v.r + (0.715 - 0.715 * s) * v.g + (0.072 + 0.928 * s) * v.b);
            o = clamp(o, 0.0, 1.0);
            o = mix(o * 0.85, 0.6375 + (o - 0.75) * 0.49, step(0.75, o));
            return half4(half3(o), 1.0);
        }
        """.trimIndent(),
    )
}

/**
 * The lens: a clear pill with a lit rim (`--lq-lens` and `--lq-lens-light`), scaled about its centre, in dp.
 * [radius] null makes it a full pill.
 */
fun DrawScope.drawLens(lens: LensDraw, tokens: GlassTokens, radius: Float? = null, offset: Offset = Offset.Zero) {
    if (lens.alpha <= .001f || lens.w <= 0f || lens.h <= 0f) return
    val d = density
    val r = min(radius ?: Float.MAX_VALUE, min(lens.w, lens.h) / 2) * d
    val rect = Rect(offset.x + lens.x * d, offset.y + lens.y * d, offset.x + (lens.x + lens.w) * d, offset.y + (lens.y + lens.h) * d)
    withTransform({ scale(lens.scaleX, lens.scaleY, rect.center) }) {
        val canvas = drawContext.canvas
        val reach = tokens.lensRing.maxOfOrNull { (abs(it.dx) + abs(it.dy) + it.spread + it.blur) * d } ?: 0f
        canvas.saveLayer(rect.inflate(2f + reach), Paint().apply { alpha = lens.alpha })
        val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }
        drawPath(shape, tokens.lens)
        clipPath(shape) { for (s in tokens.lensLight) drawInsetShadow(rect, r, s) }
        clipPath(shape, ClipOp.Difference) { for (s in tokens.lensRing) drawOuterShadow(rect, r, s) }
        canvas.restore()
    }
}

/** CSS `box-shadow` without inset: the box, grown by the spread, moved by the offset and blurred, seen outside the box. */
private fun DrawScope.drawOuterShadow(rect: Rect, r: Float, s: OuterShadow) {
    val d = density
    val sigma = s.blur / 2 * d
    val o = rect.translate(s.dx * d, s.dy * d).inflate(s.spread * d)
    val rr = r + s.spread * d
    drawIntoCanvas {
        it.nativeCanvas.drawRoundRect(o.left, o.top, o.right, o.bottom, rr, rr, android.graphics.Paint().apply {
            isAntiAlias = true
            color = s.color.toArgb()
            if (sigma > .5f) maskFilter = BlurMaskFilter(sigmaToRadius(sigma), BlurMaskFilter.Blur.NORMAL)
        })
    }
}

/** CSS `box-shadow: inset`: everything outside the box, moved by the offset and blurred, seen from inside the box. */
private fun DrawScope.drawInsetShadow(rect: Rect, r: Float, s: InsetShadow) {
    val d = density
    val sigma = s.blur / 2 * d
    val moved = rect.translate(s.dx * d, s.dy * d)
    val path = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        val o = rect.inflate(sigma * 3 + 4 * d)
        addRect(o.left, o.top, o.right, o.bottom, android.graphics.Path.Direction.CW)
        addRoundRect(moved.left, moved.top, moved.right, moved.bottom, r, r, android.graphics.Path.Direction.CW)
    }
    drawIntoCanvas {
        it.nativeCanvas.drawPath(path, android.graphics.Paint().apply {
            isAntiAlias = true
            color = s.color.toArgb()
            if (sigma > .5f) maskFilter = BlurMaskFilter(sigmaToRadius(sigma), BlurMaskFilter.Blur.NORMAL)
        })
    }
}

