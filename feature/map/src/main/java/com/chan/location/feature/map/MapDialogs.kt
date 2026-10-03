package com.chan.location.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.chan.location.core.common.GeoLatLng
import com.chan.location.core.data.model.SavedPoint
import com.chan.location.map.api.MapAdapter

/** 经纬度输入跳转后的默认缩放级别（比例尺约 200m 级） */
internal const val DEFAULT_ZOOM = 17f

/** 地图页弹窗：同一时刻至多展示一个 */
internal sealed interface MapDialog {
    data object LatLng : MapDialog

    data object Lock : MapDialog

    data class Save(
        val favorite: Boolean,
    ) : MapDialog

    data object Exit : MapDialog
}

/** 弹窗路由：同一时刻至多展示一个 */
@Composable
internal fun MapDialogHost(
    controller: MapController,
    center: GeoLatLng,
    adapter: MapAdapter?,
    onExit: () -> Unit,
) {
    val onDismiss = { controller.dialog = null }
    when (val dialog = controller.dialog) {
        MapDialog.LatLng -> LatLngJumpDialog(center, adapter, onDismiss)
        MapDialog.Lock -> LockNameDialog(center, adapter, controller::lock, onDismiss)
        is MapDialog.Save -> SaveNameDialog(dialog.favorite, center, adapter, controller, onExit)
        MapDialog.Exit -> ExitUnsavedDialog(controller, onExit)
        null -> Unit
    }
}

/** 条目定位跳转确认：使用该点启用注入，或仅留在地图查看 */
@Composable
internal fun AskUseDialog(
    point: SavedPoint,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位到该点") },
        text = { Text("已定位到「${point.name.ifBlank { "未命名位置" }}」，是否将其设为虚拟位置？") },
        confirmButton = { TextButton(onClick = onUse) { Text("使用该点") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("仅查看") } },
    )
}

@Composable
private fun LatLngJumpDialog(
    current: GeoLatLng,
    adapter: MapAdapter?,
    onDismiss: () -> Unit,
) {
    LatLngInputDialog(
        current = current,
        onConfirm = { target ->
            adapter?.moveCamera(target.lat, target.lng, adapter?.currentZoom ?: DEFAULT_ZOOM)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun LockNameDialog(
    center: GeoLatLng,
    adapter: MapAdapter?,
    onLock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NameDialog(
        title = "锁定虚拟位置",
        message = "将该位置设为虚拟位置并开始注入，同时记录到历史。",
        center = center,
        adapter = adapter,
        confirmText = "锁定",
        onConfirm = { name ->
            onDismiss()
            onLock(name)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SaveNameDialog(
    favorite: Boolean,
    center: GeoLatLng,
    adapter: MapAdapter?,
    controller: MapController,
    onExit: () -> Unit,
) {
    NameDialog(
        title = if (favorite) "收藏该位置" else "保存到历史",
        message = null,
        center = center,
        adapter = adapter,
        confirmText = "保存",
        onConfirm = { name ->
            controller.dialog = null
            controller.save(name, favorite, exitAfter = false, onExit = onExit)
        },
        onDismiss = { controller.dialog = null },
    )
}

/** 未保存退出四选一 */
@Composable
private fun ExitUnsavedDialog(
    controller: MapController,
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("退出选点") },
        text = { Text("当前选点尚未保存，是否保存？") },
        confirmButton = {
            Column {
                TextButton(
                    onClick = {
                        controller.dialog = null
                        controller.save("", false, true, onExit)
                    },
                ) { Text("保存到历史") }
                TextButton(
                    onClick = {
                        controller.dialog = null
                        controller.save("", true, true, onExit)
                    },
                ) { Text("保存并收藏") }
                TextButton(
                    onClick = {
                        controller.dialog = null
                        onExit()
                    },
                ) { Text("不保存") }
                TextButton(onClick = { controller.dialog = null }) { Text("取消") }
            }
        },
    )
}
