package com.chan.location.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.chan.location.core.common.GeoLatLng
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 比例尺基准条长：显示值取整后实际条长不超过此宽 */
private val SCALE_BAR_WIDTH = 100.dp

/**
 * 比例尺：按当前中心纬度 + 缩放级别换算真实地物长度，显示值取 1/2/5×10^n 整数。
 * 缩放级别由 MapAdapter.onZoomChanged 驱动，纬度变化（拖动）同样影响换算。
 */
@Composable
internal fun ScaleBar(
    center: GeoLatLng,
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val metersAtFull = metersPerPixel(center.lat, zoom) * SCALE_BAR_WIDTH.value * density
    val nice = niceScaleMeters(metersAtFull)
    val barWidth =
        SCALE_BAR_WIDTH * (nice / metersAtFull).toFloat().coerceIn(0.25f, 1f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(scaleText(nice), style = MaterialTheme.typography.labelSmall)
            ScaleBarLine(Modifier.width(barWidth))
        }
    }
}

/** 比例尺线段：底线 + 两端刻度 */
@Composable
private fun ScaleBarLine(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    Box(modifier.height(6.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.5.dp)
                .background(lineColor),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .width(1.5.dp)
                .height(6.dp)
                .background(lineColor),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .width(1.5.dp)
                .height(6.dp)
                .background(lineColor),
        )
    }
}

/** Web 墨卡托每像素米数（256px 瓦片标准公式，随纬度修正） */
private fun metersPerPixel(
    lat: Double,
    zoom: Float,
): Double = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom.toDouble())

/** 显示值取 1/2/5×10^n 中不超过全条长实长的最大者 */
private fun niceScaleMeters(meters: Double): Int {
    val clamped = meters.coerceAtLeast(1.0)
    val pow = 10.0.pow(floor(log10(clamped)))
    // 1×pow 必不大于 clamped（clamped ≥ pow），first 必命中
    val mult = doubleArrayOf(5.0, 2.0, 1.0).first { it * pow <= clamped }
    return (mult * pow).toInt()
}

private fun scaleText(meters: Int): String =
    if (meters >= 1000) {
        "${meters / 1000} km"
    } else {
        "$meters m"
    }
