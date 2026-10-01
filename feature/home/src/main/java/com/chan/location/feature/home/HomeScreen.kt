package com.chan.location.feature.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chan.location.core.data.PointRepository
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.data.model.SavedPoint
import com.chan.location.core.data.model.SelectedPoint
import com.chan.location.core.ui.component.ConfirmDialog
import com.chan.location.core.ui.component.MockStartGate
import com.chan.location.core.ui.component.rememberMockStartGate
import com.chan.location.service.mock.MockLocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    pointRepository: PointRepository,
    settingsRepository: SettingsRepository,
    onAddPoint: () -> Unit,
    onOpenMap: () -> Unit,
    onLocateOnMap: (SavedPoint) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data = homeData(pointRepository, settingsRepository)
    val gate =
        rememberMockStartGate(
            overlayRequired = { data.floatingEnabled },
            permissionDeniedMessage = "需要位置权限才能继续",
        )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller =
        remember(gate) {
            HomeController(context, scope, gate, pointRepository) { data.selected }
        }
    var tab by rememberSaveable { mutableStateOf(0) }
    val selection = remember { HomeSelectionState() }

    HomeContent(
        controller = controller,
        gate = gate,
        tab = tab,
        onTabChange = {
            // 多选只在当前 Tab 内有效，切页即退出
            selection.exit()
            tab = it
        },
        history = data.history,
        favorites = data.favorites,
        selected = data.selected,
        running = data.running,
        selection = selection,
        onRequestDelete = { selection.pendingDelete = true },
        onAddPoint = onAddPoint,
        onOpenMap = onOpenMap,
        onLocateOnMap = onLocateOnMap,
        onOpenSettings = onOpenSettings,
    )
    MultiDeleteConfirmDialog(controller, selection)
    gate.Dialogs()
}

/** 主页数据流聚合：列表、选中点、运行态与悬浮窗开关 */
private class HomeData(
    val history: List<SavedPoint>,
    val favorites: List<SavedPoint>,
    val selected: SelectedPoint?,
    val running: Boolean,
    val floatingEnabled: Boolean,
)

@Composable
private fun homeData(
    points: PointRepository,
    settings: SettingsRepository,
): HomeData {
    val history by points.history().collectAsStateWithLifecycle(emptyList())
    val favorites by points.favorites().collectAsStateWithLifecycle(emptyList())
    val selected by settings.selectedPoint.collectAsStateWithLifecycle(initialValue = null)
    val running by MockLocationManager.running.collectAsStateWithLifecycle(initialValue = false)
    val floatingEnabled by settings.floatingEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    return HomeData(history, favorites, selected, running, floatingEnabled)
}

/** 多选删除确认：确认后批量删除并退出多选 */
@Composable
private fun MultiDeleteConfirmDialog(
    controller: HomeController,
    selection: HomeSelectionState,
) {
    if (selection.pendingDelete) {
        ConfirmDialog(
            title = "删除点位",
            text = "删除所选 ${selection.ids.size} 个点位？该操作不可恢复。",
            confirmText = "删除",
            onConfirm = {
                controller.deleteAll(selection.ids.toList())
                selection.exit()
            },
            onDismiss = { selection.pendingDelete = false },
        )
    }
}

/** 主页整体装配：标题栏、选中卡片、列表与 FAB */
@Composable
private fun HomeContent(
    controller: HomeController,
    gate: MockStartGate,
    tab: Int,
    onTabChange: (Int) -> Unit,
    history: List<SavedPoint>,
    favorites: List<SavedPoint>,
    selected: SelectedPoint?,
    running: Boolean,
    selection: HomeSelectionState,
    onRequestDelete: () -> Unit,
    onAddPoint: () -> Unit,
    onOpenMap: () -> Unit,
    onLocateOnMap: (SavedPoint) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { gate.runWithPermissions(onAddPoint) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加点位") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HomeHeader(onOpenSettings)
            SelectedPointCard(
                selected = selected,
                running = running,
                onToggle = controller::toggle,
                onClick = {
                    // 已选点直接进地图查看当前虚拟位置；未选点与「添加点位」一致先过权限闸门
                    if (selected != null) onOpenMap() else gate.runWithPermissions(onAddPoint)
                },
            )
            PointListSection(
                tab = tab,
                onTabChange = onTabChange,
                history = history,
                favorites = favorites,
                selection = selection,
                onRequestDelete = onRequestDelete,
                onUse = controller::usePoint,
                onLocate = onLocateOnMap,
                onAddPoint = { gate.runWithPermissions(onAddPoint) },
                onFavorite = controller::setFavorite,
                onDelete = controller::delete,
            )
        }
    }
}

/** 主页操作集合：启停、使用/收藏/删除点位，统一经 MockStartGate 校验链 */
private class HomeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gate: MockStartGate,
    private val repo: PointRepository,
    private val selected: () -> SelectedPoint?,
) {
    fun toggle(enable: Boolean) {
        if (!enable) {
            MockLocationManager.stop()
        } else {
            val point = selected()
            if (point == null) {
                showToast(context, "请先选择位置")
            } else {
                start(point)
            }
        }
    }

    fun usePoint(item: SavedPoint) {
        gate.requestStart {
            scope.launch {
                repo.touch(item.id)
                MockLocationManager.start(SelectedPoint(item.name, item.wgsLat, item.wgsLng))
            }
        }
    }

    fun setFavorite(item: SavedPoint) {
        scope.launch { repo.setFavorite(item.id, !item.isFavorite) }
    }

    fun delete(item: SavedPoint) {
        scope.launch { repo.delete(item.id) }
    }

    fun deleteAll(ids: List<Long>) {
        scope.launch { repo.deleteAll(ids) }
    }

    private fun start(point: SelectedPoint) =
        gate.requestStart {
            scope.launch { MockLocationManager.start(point) }
        }
}

private fun showToast(
    context: Context,
    message: String,
) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
