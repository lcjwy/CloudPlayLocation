package com.chan.location.feature.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chan.location.core.common.GeoLatLng
import com.chan.location.core.common.MapSource
import com.chan.location.core.common.MockCheck
import com.chan.location.core.data.PointRepository
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.data.model.SavedPoint
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

/** 退出页面后延迟销毁地图实例，避开返回动画 */
private const val DESTROY_DELAY_MS = 300L

@Composable
fun MapScreen(
    pointRepository: PointRepository,
    settingsRepository: SettingsRepository,
    mapAdapterFactory: MapAdapterFactory,
    onExit: () -> Unit,
    focusPointId: Long? = null,
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
    val handle =
        rememberMapAdapter(
            settingsRepository,
            mapAdapterFactory,
            pointRepository,
            focusPointId,
        )
    val adapter = handle.adapter
    MapLifecycle(adapter)
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
        zoom = handle.zoom,
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
    handle.askUse?.let { point ->
        AskUseDialog(
            point = point,
            onUse = {
                handle.askUse = null
                controller.usePoint(point)
            },
            onDismiss = { handle.askUse = null },
        )
    }
}

/** 创建并持有地图实例与选点状态：初始视角优先定位跳转点、其次上次选点，未同意隐私强制 OSM */
@Composable
private fun rememberMapAdapter(
    settingsRepository: SettingsRepository,
    mapAdapterFactory: MapAdapterFactory,
    pointRepository: PointRepository,
    focusPointId: Long?,
): MapHandle {
    val context = LocalContext.current
    val state = remember { MapHandle() }
    val destroyHandler = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(mapAdapterFactory) {
        val setup =
            buildMapSetup(
                context,
                settingsRepository,
                mapAdapterFactory,
                pointRepository,
                focusPointId,
            )
        applySetup(setup, state)
    }
    DisposableEffect(Unit) {
        onDispose {
            // onDestroy 的 GL/JNI 同步释放发生在返回动画期间会掉帧：延迟到动画结束后执行
            val adapter = state.adapter
            state.adapter = null
            destroyHandler.postDelayed(
                {
                    adapter?.onPause()
                    adapter?.onDestroy()
                },
                DESTROY_DELAY_MS,
            )
        }
    }
    return state
}

/** 地图跟随页面前后台状态，后台立即停止蓝点定位。 */
@Composable
private fun MapLifecycle(adapter: MapAdapter?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, adapter) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> adapter?.onResume()
                    Lifecycle.Event.ON_PAUSE -> adapter?.onPause()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        // 注册时补齐初始状态：adapter 异步创建完成时生命周期往往已 RESUMED，
        // ON_RESUME 事件不会重发，观察者收不到——必须主动补一次 onResume
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            adapter?.onResume()
        } else {
            adapter?.onPause()
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            adapter?.onPause()
        }
    }
}

/** 装配落位：回调接线 + 初始状态写入（中心/提交点/缩放/待确认点位） */
private fun applySetup(
    setup: MapSetup,
    state: MapHandle,
) {
    setup.adapter.onCenterChanged = { lat, lng ->
        state.center = GeoLatLng(lat, lng)
    }
    setup.adapter.onZoomChanged = { zoom ->
        state.zoom = zoom
    }
    state.adapter = setup.adapter
    state.myLocationEnabled = setup.myLocationEnabled
    state.center = setup.initial
    state.committed = setup.initial
    state.zoom = setup.initialZoom
    state.askUse = setup.focus
}

/** 地图初始装配结果：适配器实例、初始视角/缩放与蓝点开关 */
private class MapSetup(
    val adapter: MapAdapter,
    val initial: GeoLatLng,
    val initialZoom: Float,
    val myLocationEnabled: Boolean,
    val focus: SavedPoint?,
)

private suspend fun buildMapSetup(
    context: Context,
    settings: SettingsRepository,
    factory: MapAdapterFactory,
    repo: PointRepository,
    focusPointId: Long?,
): MapSetup {
    val source = settings.mapSource.first()
    val privacy = settings.privacyAgreed.first() == true
    val selected = settings.selectedPoint.first()
    val focus = focusPointId?.let { repo.byId(it) }
    val initial =
        focus?.let { GeoLatLng(it.wgsLat, it.wgsLng) }
            ?: selected?.let { GeoLatLng(it.wgsLat, it.wgsLng) }
            ?: DEFAULT_POINT
    // 入场统一默认比例：近景级别会把米级坐标系换算误差放大到肉眼可见
    val withMyLocation = privacy && MockCheck.hasLocationPermission(context)
    val adapter =
        factory.create(
            context,
            MapConfig(
                source = if (privacy) source else MapSource.OSM,
                lat = initial.lat,
                lng = initial.lng,
                zoom = DEFAULT_ZOOM,
                myLocationEnabled = withMyLocation,
                privacyAgreed = privacy,
            ),
        )
    return MapSetup(adapter, initial, DEFAULT_ZOOM, withMyLocation, focus)
}

/** 地图实例 + 选点状态；askUse 为条目定位跳转带来的待确认点位 */
internal class MapHandle {
    var adapter by mutableStateOf<MapAdapter?>(null)
    var myLocationEnabled by mutableStateOf(false)
    var center by mutableStateOf(DEFAULT_POINT)
    var committed by mutableStateOf(DEFAULT_POINT)
    var zoom by mutableStateOf(DEFAULT_ZOOM)
    var askUse by mutableStateOf<SavedPoint?>(null)
}

/** 地图页操作：弹窗流转、保存/收藏、锁定并启动注入 */
internal class MapController(
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

    /** 保存/收藏：名称缺省时反查地址，两层均失败传空串（由仓库保留原名/坐标兜底）；exitAfter 为退出选点页时的四选一保存 */
    fun save(
        name: String,
        favorite: Boolean,
        exitAfter: Boolean,
        onExit: () -> Unit,
    ) {
        scope.launch {
            val finalName = name.ifBlank { reverseGeocode(context, handle.adapter, center) ?: "" }
            if (favorite) {
                repo.saveFavorite(finalName, center)
            } else {
                repo.saveHistory(finalName, center)
            }
            commit()
            if (exitAfter) onExit()
        }
    }

    /** 锁定虚拟位置：校验链通过后落历史并启动注入；选中点名取落库后的最终名称 */
    fun lock(name: String) {
        gate.requestStart {
            scope.launch {
                val finalName =
                    name.ifBlank {
                        reverseGeocode(
                            context,
                            handle.adapter,
                            center,
                        ) ?: ""
                    }
                val saved = repo.saveHistory(finalName, center)
                MockLocationManager.start(SelectedPoint(saved.name, center.lat, center.lng))
                commit()
            }
        }
    }

    /** 条目定位跳转后确认使用：与主页条目点击一致，校验链通过后落历史并注入 */
    fun usePoint(item: SavedPoint) {
        gate.requestStart {
            scope.launch {
                repo.touch(item.id)
                MockLocationManager.start(SelectedPoint(item.name, item.wgsLat, item.wgsLng))
            }
        }
    }
}

/** 弹窗族与弹窗路由见 MapDialogs.kt */
