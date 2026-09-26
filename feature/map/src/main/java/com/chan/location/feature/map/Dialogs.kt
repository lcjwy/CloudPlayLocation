package com.chan.location.feature.map

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chan.location.core.common.CoordUtils
import com.chan.location.core.common.GeoLatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 系统 Geocoder 反查地址；失败回退为坐标文本 */
suspend fun reverseGeocode(
    context: Context,
    point: GeoLatLng,
): String =
    withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.CHINA)
                .getFromLocation(point.lat, point.lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.US, "%.6f, %.6f", point.lat, point.lng)
    }

/** 名称确认弹窗：默认名异步反查，用户修改后不再覆盖。不确认不落库 */
@Composable
internal fun NameDialog(
    title: String,
    message: String?,
    center: GeoLatLng,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(center) {
        if (!touched) name = reverseGeocode(context, center)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        touched = true
                    },
                    label = { Text("位置名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(name) }) { Text(confirmText) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 经纬度输入弹窗：校验范围，可选坐标系（BD09 输入自动转 WGS84） */
@Composable
internal fun LatLngInputDialog(
    current: GeoLatLng,
    onConfirm: (GeoLatLng) -> Unit,
    onDismiss: () -> Unit,
) {
    var latText by remember {
        mutableStateOf(String.format(Locale.US, "%.6f", current.lat))
    }
    var lngText by remember {
        mutableStateOf(String.format(Locale.US, "%.6f", current.lng))
    }
    var useBd09 by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val lat = latText.toDoubleOrNull()
        val lng = lngText.toDoubleOrNull()
        if (lat == null || lng == null || !inRange(lat, lng)) {
            error = "坐标格式不正确或超出范围"
            return
        }
        val wgs = if (useBd09) CoordUtils.bd092wgs(GeoLatLng(lat, lng)) else GeoLatLng(lat, lng)
        onConfirm(wgs)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("经纬度定位") },
        text = {
            CoordinateFields(
                latText = latText,
                lngText = lngText,
                onLatChange = { latText = it },
                onLngChange = { lngText = it },
                useBd09 = useBd09,
                onBd09Change = { useBd09 = it },
                error = error,
            )
        },
        confirmButton = { TextButton({ submit() }) { Text("定位") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

private fun inRange(
    lat: Double,
    lng: Double,
): Boolean = lat in -90.0..90.0 && lng in -180.0..180.0

@Composable
private fun CoordinateFields(
    latText: String,
    lngText: String,
    onLatChange: (String) -> Unit,
    onLngChange: (String) -> Unit,
    useBd09: Boolean,
    onBd09Change: (Boolean) -> Unit,
    error: String?,
) {
    Column {
        OutlinedTextField(
            value = latText,
            onValueChange = onLatChange,
            label = { Text("纬度 (-90 ~ 90)") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = lngText,
            onValueChange = onLngChange,
            label = { Text("经度 (-180 ~ 180)") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useBd09,
                onClick = { onBd09Change(false) },
                label = { Text("WGS84") },
            )
            FilterChip(
                selected = useBd09,
                onClick = { onBd09Change(true) },
                label = { Text("BD09（百度）") },
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
