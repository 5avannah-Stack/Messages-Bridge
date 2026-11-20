package cz.teous.messages.bridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import cz.teous.messages.bridge.contract.BridgeContract

/** Line icons on a 24 grid, the same paths as Messages' network marks. */
object Icons {
    private fun icon(name: String, stroked: List<String>, filled: List<String> = emptyList()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroked.forEach {
                addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            filled.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
        }.build()

    private fun dot(x: Float, y: Float, r: Float) = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val WhatsApp = icon("wa", listOf("M12 3a9 9 0 0 0-7.7 13.6L3 21l4.5-1.2A9 9 0 1 0 12 3z", "M9 8.3c-.6.5-.9 1.3-.6 2.2a8 8 0 0 0 5.1 5.1c.9.3 1.7 0 2.2-.6l.3-1-2-1-.8.8a5 5 0 0 1-2.4-2.4l.8-.8-1-2z"))
    val Signal = icon("signal", listOf("M12 3.5a8.5 8.5 0 0 0-7.3 12.9L3.5 20.5l4.1-1.2A8.5 8.5 0 1 0 12 3.5z"))
    val Instagram = icon("ig", listOf("M8.5 4h7A4.5 4.5 0 0 1 20 8.5v7a4.5 4.5 0 0 1-4.5 4.5h-7A4.5 4.5 0 0 1 4 15.5v-7A4.5 4.5 0 0 1 8.5 4z", "M8.2 12a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0z"), listOf(dot(16.8f, 7.2f, .8f)))
    val Message = icon("msg", listOf("M6 4.5h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3h-8l-4.5 3.5v-3.5H6a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3z"))
    val Bell = icon("bell", listOf("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15zM10 20.5a2 2 0 0 0 4 0"))
    val Code = icon("code", listOf("M8.5 7.5 4 12l4.5 4.5M15.5 7.5 20 12l-4.5 4.5M13.5 5.5l-3 13"))
    val Chevron = icon("chev", listOf("M9 6l6 6-6 6"))

    fun network(id: String): ImageVector = when (id) {
        BridgeContract.WHATSAPP -> WhatsApp
        BridgeContract.SIGNAL -> Signal
        BridgeContract.INSTAGRAM -> Instagram
        else -> Message
    }
}
