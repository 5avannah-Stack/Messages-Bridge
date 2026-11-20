package cz.teous.messages.bridge.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import cz.teous.liquid.GlassTokens
import cz.teous.liquid.InsetShadow
import cz.teous.liquid.LensDraw
import cz.teous.liquid.LocalGlassTokens
import cz.teous.liquid.drawLens

/*
 * Content surfaces, as Messages draws them (teodor-taste, Content; the kit's content-lab.html): drawn with the kit's
 * lens code and tokens, never blurred. A rim card takes the theme's card light; discs and buttons the lens's own.
 */
private fun GlassTokens.cardLight() = listOf(
    InsetShadow(0f, 1.5f, 1f, rim.copy(alpha = rimTop * .64f)),
    InsetShadow(0f, -1.5f, 1f, rim.copy(alpha = rimBottom * .73f)),
)

private fun Modifier.lensSurface(radius: Dp, tokens: (GlassTokens) -> GlassTokens, ring: Boolean = false): Modifier = composed {
    val base = LocalGlassTokens.current
    val look = tokens(base)
    val ringColor = base.rim.copy(alpha = base.rimBase * .65f)
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
        val d = density
        drawLens(LensDraw(0f, 0f, size.width / d, size.height / d, 1f, 1f, 1f), look, radius.value)
        if (ring) {
            val r = radius.toPx()
            drawRoundRect(ringColor, cornerRadius = CornerRadius(r - d / 2), topLeft = Offset(d / 2, d / 2), size = Size(size.width - d, size.height - d), style = Stroke(d))
        }
    }
}

/** A rim card: the liquid rim on a faint fill. */
fun Modifier.rimCard(radius: Dp): Modifier = lensSurface(radius, { it.copy(lens = Color.White.copy(alpha = .035f), lensLight = it.cardLight()) }, ring = true)

/** The lens glass itself, for discs and buttons. */
fun Modifier.rowGlass(radius: Dp): Modifier = lensSurface(radius, { it })
