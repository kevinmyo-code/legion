package com.kevin.legion.ui.claudesession

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The 24 h temperature line under a machine's health (laptop-health 2026-11-10): the hotter of CPU
 * and GPU every 10 minutes, oldest on the left, with its low and high in words beside it so the
 * line is never the only way to read it.
 */
@Composable
fun LaptopSparkline(values: List<Float>) {
    val lo = values.min()
    val hi = values.max()
    val words = "Last 24 h: ${lo.toInt()} to ${hi.toInt()} C"
    val line = SoftColors.primary
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(SPARK_HEIGHT)
                .testTag(SPARK_TAG)
                .semantics { contentDescription = words },
        ) {
            val span = (hi - lo).coerceAtLeast(1f)
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val p = Offset(i * step, size.height - (v - lo) / span * size.height)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
        Text(words, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
    }
}

private val SPARK_HEIGHT = 32.dp
const val SPARK_TAG = "laptop-health-spark"
