package com.chan.location.feature.map

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chan.location.core.common.GeoLatLng
import com.chan.location.core.common.MapSource
import com.chan.location.core.common.MockCheck
import com.chan.location.core.data.PointRepository
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.data.model.SelectedPoint
import com.chan.location.core.ui.component.MockStartGate
import com.chan.location.core.ui.component.rememberMockStartGate
import com.chan.location.map.api.MapAdapter
import com.chan.location.map.api.MapAdapterFactory
import com.chan.location.map.api.MapConfig
import com.chan.location.service.mock.MockLocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 无选点时的默认视角（北京，WGS84） */
private val DEFAULT_POINT = GeoLatLng(39.908722, 116.397499)

/** 经纬度输入跳转后的默认缩放级别 */
private const val DEFAULT_ZOOM = 16f

/** 地图页弹窗：同一时刻至多展示一个 */
private sealed interface MapDialog {
    data object LatLng : MapDialog

    data object Lock : MapDialog

    data class Save(
        val favorite: Boolean,
    ) : MapDialog

    data object Exit : MapDialog
}

@Composable
fun MapScreen(
    pointRepository: PointRepository,
    settingsRepository: SettingsRepository,
    mapAdapterFactory: MapAdapterFactory,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val floatingEnabled by settingsRepository.floatingEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val gate =
        rememberMockStartGate(
            overlayRequired = { floatingEnabled },
            permissionDeniedMessage = "需要位置权限才能锁定位置",
        )
    val handle = rememberMapAdapter(settingsRepository, mapAdapterFactory)
    val adapter = handle.adapter
    val unsaved = !handle.center.nearlyEquals(handle.committed)
    val context = LocalContext.current
    val controller = remember(gate) { MapController(context, scope, pointRepository, gate, handle) }

    BackHandler(enabled = unsaved) { controller.dialog = MapDialog.Exit }

    MapScreenContent(controller, gate, adapter, handle, unsaved, onExit)
}

/** 地图画布 + 弹窗 + 启停闸门弹窗的整体装配 */
@Composable
private fun MapScreenContent(
    controller: MapController,
    gate: MockStartGate,
    adapter: MapAdapter?,
    handle: MapHandle,
    unsaved: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val center = handle.center
    MapSurface(
        adapter = adapter,
        center = center,
        unsaved = unsaved,
        myLocationEnabled = handle.myLocationEnabled,
        onBack = { if (unsaved) controller.dialog = MapDialog.Exit else onExit() },
        onLocate = {
            if (adapter?.moveToMyLocation() != true) {
                Toast.makeText(context, "暂无定位结果", Toast.LENGTH_SHORT).show()
            }
        },
        onInput = { controller.dialog = MapDialog.LatLng },
        onSave = { controller.dialog = MapDialog.Save(favorite = false) },
        onFavorite = { controller.dialog = MapDialog.Save(favorite = true) },
        onLock = { controller.dialog = MapDialog.Lock },
    )
    MapDialogHost(controller, center, adapter, onExit)
    gate.Dialogs()
}

@Composable
private fun MapDialogHost(
    controller: MapController,
    center: GeoLatLng,
    adapter: MapAdapter?,
    onExit: () -> Unit,
) {
    val onDismiss = { controller.dialog = null }
    when (val dialog = controller.dialog) {
        MapDialog.LatLng -> LatLngJumpDialog(center, adapter, onDismiss)
        MapDialog.Lock -> LockNameDialog(center, controller::lock, onDismiss)
        is MapDialog.Save -> SaveNameDialog(dialog.favorite, center, controller, onExit)
        MapDialog.Exit -> ExitUnsavedDialog(controller, onExit)
        null -> Unit
    }
}

/** 创建并持有地图实例与选点状态：初始视角优先上次选点，未同意隐私强制 OSM */
@Composable
private fun rememberMapAdapter(
    settingsRepository: SettingsRepository,
    mapAdapterFactory: MapAdapterFactory,
): MapHandle {
    val context = LocalContext.current
    val state = remember { MapHandle() }
    LaunchedEffect(mapAdapterFactory) {
        val source = settingsRepository.mapSource.first()
        val privacy = settingsRepository.privacyAgreed.first() == true
        val selected = settingsRepository.selectedPoint.first()
        val initial = selected?.let { GeoLatLng(it.wgsLat, it.wgsLng) } ?: DEFAULT_POINT
        val withMyLocation = privacy && MockCheck.hasLocationPermission(context)
        val created =
            mapAdapterFactory.create(
                context,
                MapConfig(
                    source = if (privacy) source else MapSource.OSM,
                    lat = initial.lat,
                    lng = initial.lng,
                    myLocationEnabled = withMyLocation,
                    privacyAgreed = privacy,
                ),
            )
        created.onCenterChanged = { lat, lng ->
            state.center = GeoLatLng(lat, lng)
        }
        created.onResume()
        state.adapter = created
        state.myLocationEnabled = withMyLocation
        state.center = initial
        state.committed = initial
    }
    DisposableEffect(Unit) {
        onDispose {
            state.adapter?.onPause()
            state.adapter?.onDestroy()
        }
    }
    return state
}

/** 地图实例 + 选点状态（center 随拖动更新，committed 为已保存快照） */
private class MapHandle {
    var adapter by mutableStateOf<MapAdapter?>(null)
    var myLocationEnabled by mutableStateOf(false)
    var center by mutableStateOf(DEFAULT_POINT)
    var committed by mutableStateOf(DEFAULT_POINT)
}

/** 地图页操作：弹窗流转、保存/收藏、锁定并启动注入 */
private class MapController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: PointRepository,
    private val gate: MockStartGate,
    private val handle: MapHandle,
) {
    var dialog by mutableStateOf<MapDialog?>(null)

    private val center: GeoLatLng get() = handle.center

    private fun commit() {
        handle.committed = handle.center
    }

    /** 保存/收藏：名称缺省时反查地址；exitAfter 为退出选点页时的四选一保存 */
    fun save(
        name: String,
        favorite: Boolean,
        exitAfter: Boolean,
        onExit: () -> Unit,
    ) {
        scope.launch {
            val finalName = name.ifBlank { reverseGeocode(context, center) }
            if (favorite) {
                repo.saveFavorite(finalName, center)
            } else {
                repo.saveHistory(finalName, center)
            }
            commit()
            if (exitAfter) onExit()
        }
    }

    /** 锁定虚拟位置：校验链通过后落历史并启动注入 */
    fun lock(name: String) {
        gate.requestStart {
            scope.launch {
                val finalName = name.ifBlank { reverseGeocode(context, center) }
                repo.saveHistory(finalName, center)
                MockLocationManager.start(SelectedPoint(finalName, center.lat, center.lng))
                commit()
            }
        }
    }
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
    onLock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NameDialog(
        title = "锁定虚拟位置",
        message = "将该位置设为虚拟位置并开始注入，同时记录到历史。",
        center = center,
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
    controller: MapController,
    onExit: () -> Unit,
) {
    NameDialog(
        title = if (favorite) "收藏该位置" else "保存到历史",
        message = null,
        center = center,
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
