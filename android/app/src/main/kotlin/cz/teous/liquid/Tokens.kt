package cz.teous.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset

/**
 * The look tokens: liquid.js's `--lq-*` custom properties. An app overrides them with [LiquidTheme] and never edits
 * the port. Lengths are dp, the web's CSS pixels. A theme is a set of them: switching the tokens [LiquidTheme]
 * provides switches the theme at runtime, as `Liquid.setTheme` does on the web.
 */
@Immutable
data class GlassTokens(
    /** `--lq-fill`: the mist laid over the bent backdrop. */
    val fill: Color = Color(38, 38, 42).copy(alpha = .46f),
    /** `--lq-filter` without refraction (API 31 and 32): `blur(9px) saturate(1.6) brightness(.82)`. */
    val blur: Float = 9f,
    val saturation: Float = 1.6f,
    val brightness: Float = .82f,
    /** Below API 31 there is no blur: the glass is this opaque fill (phone-apps design.md, "Opaque fallback"). */
    val opaque: Color = Color(0xFF121214),
    /** `--lq-refraction`: bend the backdrop at the rim (API 33+), as Chromium does on the web. */
    val refraction: Boolean = true,
    /** `--lq-rim`: the colour of the rim's light and the inner glow. */
    val rim: Color = Color.White,
    /** `--lq-rim-width` and `--lq-rim-soft`: how deep the rim's band reaches in from the outline, and its softening. */
    val rimWidth: Float = 2.5f,
    val rimSoft: Float = 1.1f,
    /** `--lq-rim-base` over the whole outline, then `--lq-rim-top`, `-upper`, `-lower` and `-bottom` across each piece. */
    val rimBase: Float = .1f,
    val rimTop: Float = .5f,
    val rimUpper: Float = .044f,
    val rimLower: Float = 0f,
    val rimBottom: Float = .178f,
    /** `--lq-glow` and `--lq-glow-width`: the faint light inside the edge, and how far in it reaches. */
    val glow: Float = .14f,
    val glowWidth: Float = 9f,
    val text: Color = Color.White,
    val text2: Color = Color.White.copy(alpha = .88f),
    val text3: Color = Color.White.copy(alpha = .74f),
    /** `--lq-text-shadow`: `0 1px 2px rgba(0,0,0,.5), 0 0 10px rgba(0,0,0,.28)`. */
    val textShadow: List<Shadow> = listOf(
        Shadow(Color.Black.copy(alpha = .28f), Offset.Zero, 10f),
        Shadow(Color.Black.copy(alpha = .5f), Offset(0f, 1f), 2f),
    ),
    /** `--lq-lens`: the lens's own fill. */
    val lens: Color = Color.White.copy(alpha = .06f),
    /** `--lq-lens-light`'s inset shadows, which light the lens's rim. */
    val lensLight: List<InsetShadow> = listOf(
        InsetShadow(0f, 1.5f, 2f, Color.White.copy(alpha = .42f)),
        InsetShadow(0f, -1.5f, 2f, Color.White.copy(alpha = .16f)),
        InsetShadow(0f, 0f, 12f, Color.White.copy(alpha = .08f)),
    ),
    /** `--lq-lens-light`'s outer shadows, drawn around the lens: a ring and a glow. Liquid has none. */
    val lensRing: List<OuterShadow> = emptyList(),
    /** Dock icons: white at this alpha away from the lens, full white under it. */
    val iconDim: Float = .62f,
    val onGlass: Color = Color(0xFFF2F2F2),
    val canvas: Color = Color.Black,
    val red: Color = Color(0xFFFF5C62),
)

/**
 * Noir: liquid.js's `:root[data-lq-theme="noir"]` at phone width (680 px and below): darker smoke glass and liquid's
 * band at 40 % in grey, with liquid's text, lens and bend. An app passes it to [LiquidTheme] when the person picks Noir.
 */
val NoirTokens = GlassTokens(
    fill = Color(16, 16, 18).copy(alpha = .72f),
    blur = 14f,
    saturation = 1.1f,
    brightness = .7f,
    rim = Color(0xFFADADAD),
    rimBase = .04f,
    rimTop = .2f,
    rimUpper = .0176f,
    rimLower = 0f,
    rimBottom = .0712f,
    glow = .056f,
)

/** A CSS `box-shadow: inset dx dy blur color`, in dp. */
@Immutable
data class InsetShadow(val dx: Float, val dy: Float, val blur: Float, val color: Color)

/** A CSS `box-shadow: dx dy blur spread color`, in dp: `OuterShadow(0f, 0f, 0f, 1f, …)` is a 1 dp ring. */
@Immutable
data class OuterShadow(val dx: Float, val dy: Float, val blur: Float, val spread: Float, val color: Color)

val LocalGlassTokens = staticCompositionLocalOf { GlassTokens() }

@Composable
fun LiquidTheme(tokens: GlassTokens = GlassTokens(), content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGlassTokens provides tokens, content = content)
