package com.chan.location.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chan.location.core.common.GeoLatLng
import com.chan.location.map.api.MapAdapter
import java.util.Locale

/** 地图页「我的位置」按钮：蓝色，与地图蓝点语义一致 */
private val MY_LOCATION_TINT = Color(0xFF2196F3)

/** 地图画布 + 全部悬浮控件 */
@Composable
internal fun MapSurface(
    adapter: MapAdapter?,
    center: GeoLatLng,
    unsaved: Boolean,
    myLocationEnabled: Boolean,
    onBack: () -> Unit,
    onLocate: () -> Unit,
    onInput: () -> Unit,
    onSave: () -> Unit,
    onFavorite: () -> Unit,
    onLock: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        adapter?.let { created ->
            AndroidView(factory = { created.view }, modifier = Modifier.fillMaxSize())
        }
        Crosshair(Modifier.fillMaxSize())
        MapTopBar(
            onBack = onBack,
            myLocationEnabled = myLocationEnabled,
            onLocate = onLocate,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        ZoomControls(
            onZoomIn = { adapter?.zoomIn() },
            onZoomOut = { adapter?.zoomOut() },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        BottomPanel(
            center = center,
            unsaved = unsaved,
            onInput = onInput,
            onSave = onSave,
            onFavorite = onFavorite,
            onLock = onLock,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

/** 屏幕中心固定准星：拖动地图即选点 */
@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val gap = 14.dp.toPx()
        val len = 20.dp.toPx()
        val passes =
            listOf(
                Color.Black.copy(alpha = 0.6f) to 4.dp.toPx(),
                Color.White to 2.dp.toPx(),
            )
        passes.forEach { (color, width) ->
            drawLine(color, Offset(cx - gap - len, cy), Offset(cx - gap, cy), strokeWidth = width)
            drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + len, cy), strokeWidth = width)
            drawLine(color, Offset(cx, cy - gap - len), Offset(cx, cy - gap), strokeWidth = width)
            drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + gap + len), strokeWidth = width)
        }
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = 5.dp.toPx(), center = Offset(cx, cy))
        drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapTopBar(
    onBack: () -> Unit,
    myLocationEnabled: Boolean,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text("地图") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            if (myLocationEnabled) {
                IconButton(onClick = onLocate) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = "我的位置",
                        tint = MY_LOCATION_TINT,
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircleButton(onClick = onZoomIn) {
            Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        CircleButton(onClick = onZoomOut) {
            Text("−", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomPanel(
    center: GeoLatLng,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
    onInput: () -> Unit,
    onSave: () -> Unit,
    onFavorite: () -> Unit,
    onLock: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "纬度 %.6f   经度 %.6f", center.lat, center.lng),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (unsaved) "WGS84 · 未保存" else "WGS84",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (unsaved) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AssistButton(Icons.Default.Edit, "输入", onInput)
                AssistButton(Icons.Default.Done, "保存", onSave)
                AssistButton(Icons.Default.Favorite, "收藏", onFavorite)
                Button(onClick = onLock) {
                    Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("锁定")
                }
            }
        }
    }
}

@Composable
private fun AssistButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(label)
    }
}
