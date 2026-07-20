package com.watchoutrf.desktop.ui.components.spectrum

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchoutrf.desktop.domain.model.Marker
import com.watchoutrf.desktop.domain.model.MarkerColor
import com.watchoutrf.desktop.ui.theme.AmberYellow
import com.watchoutrf.desktop.ui.theme.CyanBright
import com.watchoutrf.desktop.ui.theme.ErrorRed
import com.watchoutrf.desktop.ui.theme.NeonGreen

/**
 * Translucent overlay that renders spectrum markers on top of the spectrum plot.
 *
 * For each [Marker]:
 * - A dashed vertical cursor line spanning the full height at 0.3 alpha.
 * - A small filled diamond at the marker's (frequency, amplitude) intersection.
 * - An identifier label above the diamond (e.g. "M1").
 * - A detail label below the diamond showing frequency and amplitude.
 *
 * @param markers        List of active markers.
 * @param startHz        Display start frequency in Hz.
 * @param endHz          Display end frequency in Hz.
 * @param referenceLevel Reference level in dBm (top of display).
 * @param dynamicRange   Vertical dynamic range in dB.
 * @param textMeasurer   A [TextMeasurer] from the caller.
 * @param modifier       Compose modifier.
 */
@Composable
fun MarkerOverlay(
    markers: List<Marker>,
    startHz: Long,
    endHz: Long,
    referenceLevel: Float,
    dynamicRange: Float,
    textMeasurer: TextMeasurer,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val spanHz = (endHz - startHz).toFloat()
        if (spanHz <= 0f) return@Canvas

        for (marker in markers) {
            val color = marker.color.toComposeColor()
            val x = w * (marker.frequencyHz - startHz).toFloat() / spanHz
            val y = (h * (referenceLevel - marker.amplitudeDbm) / dynamicRange).coerceIn(0f, h)

            // ---- Vertical dashed cursor ----
            drawLine(
                color = color.copy(alpha = 0.3f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )

            // ---- Diamond at peak ----
            drawDiamond(center = Offset(x, y), radius = 4.dp.toPx(), color = color)

            // ---- Identifier label above ----
            val idLabel = marker.label
            val idStyle = TextStyle(
                color = Color.White,
                fontSize = 9.sp, // 20% smaller
                fontWeight = FontWeight.Bold
            )
            val idLayout = textMeasurer.measure(idLabel, idStyle)
            val idX = (x - idLayout.size.width / 2f).coerceIn(0f, w - idLayout.size.width)
            val idY = (y - 4.dp.toPx() - idLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f)
            
            // Draw background for the label
            drawRect(
                color = Color(0xAA000000),
                topLeft = Offset(idX - 2.dp.toPx(), idY - 1.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(idLayout.size.width + 4.dp.toPx(), idLayout.size.height + 2.dp.toPx())
            )
            drawText(textLayoutResult = idLayout, topLeft = Offset(idX, idY))
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Draws a filled diamond (rotated square) centred at [center] with the given [radius]. */
private fun DrawScope.drawDiamond(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)      // top
        lineTo(center.x + radius, center.y)       // right
        lineTo(center.x, center.y + radius)       // bottom
        lineTo(center.x - radius, center.y)       // left
        close()
    }
    drawPath(path = path, color = color, style = Fill)
}

/** Maps a [MarkerColor] domain enum to its corresponding Compose [Color]. */
private fun MarkerColor.toComposeColor(): Color = when (this) {
    MarkerColor.CYAN    -> CyanBright
    MarkerColor.YELLOW  -> AmberYellow
    MarkerColor.MAGENTA -> Color(0xFFFF69B4)
    MarkerColor.GREEN   -> Color.White // Changed from Green to White for contrast
    MarkerColor.RED     -> ErrorRed
    MarkerColor.BLUE    -> AmberYellow // Changed from Blue to Yellow for contrast
    MarkerColor.ORANGE  -> Color(0xFFFF9800)
    MarkerColor.WHITE   -> Color.White
}
