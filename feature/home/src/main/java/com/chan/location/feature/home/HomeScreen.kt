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
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val history by pointRepository.history().collectAsStateWithLifecycle(emptyList())
    val favorites by pointRepository.favorites().collectAsStateWithLifecycle(emptyList())
    val selected by settingsRepository.selectedPoint.collectAsStateWithLifecycle(
        initialValue = null,
    )
    val running by MockLocationManager.running.collectAsStateWithLifecycle(initialValue = false)
    val floatingEnabled by settingsRepository.floatingEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )

    var tab by rememberSaveable { mutableStateOf(0) }
    val gate =
        rememberMockStartGate(
            overlayRequired = { floatingEnabled },
            permissionDeniedMessage = "需要位置权限才能继续",
        )
    val controller =
        remember(gate) {
            HomeController(context, scope, gate, pointRepository) { selected }
        }

    HomeContent(controller, gate, tab, {
        tab = it
    }, history, favorites, selected, running, onAddPoint, onOpenSettings)

    gate.Dialogs()
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
    onAddPoint: () -> Unit,
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
            SelectedPointCard(selected, running, onToggle = controller::toggle)
            PointListSection(
                tab = tab,
                onTabChange = onTabChange,
                history = history,
                favorites = favorites,
                onUse = controller::usePoint,
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
