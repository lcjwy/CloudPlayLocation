package com.chan.location.feature.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chan.location.core.data.model.SavedPoint
import com.chan.location.core.data.model.SelectedPoint
import com.chan.location.core.ui.component.EmptyState
import java.util.Locale

@Composable
internal fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "云游",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}

@Composable
internal fun SelectedPointCard(
    selected: SelectedPoint?,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = selected?.name?.ifBlank { "未命名位置" } ?: "未选择位置",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text =
                        selected
                            ?.let { String.format(Locale.US, "%.6f, %.6f", it.wgsLat, it.wgsLng) }
                            ?: "点击卡片去地图选点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = running, onCheckedChange = onToggle)
                Text(
                    text = if (running) "运行中" else "已停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ColumnScope 扩展：直接以 weight 占据 HomeScreen 纵向剩余空间 */
@Composable
internal fun ColumnScope.PointListSection(
    tab: Int,
    onTabChange: (Int) -> Unit,
    history: List<SavedPoint>,
    favorites: List<SavedPoint>,
    selection: HomeSelectionState,
    onRequestDelete: () -> Unit,
    onUse: (SavedPoint) -> Unit,
    onLocate: (SavedPoint) -> Unit,
    onAddPoint: () -> Unit,
    onFavorite: (SavedPoint) -> Unit,
    onDelete: (SavedPoint) -> Unit,
) {
    val list = if (tab == 0) history else favorites
    // 列表删空后自动退出多选，避免停留在无条目的多选态
    LaunchedEffect(selection.selecting, list.isEmpty()) {
        if (selection.selecting && list.isEmpty()) selection.exit()
    }
    if (selection.selecting) {
        SelectionBar(
            selectedCount = selection.ids.size,
            allSelected = list.isNotEmpty() && list.all { it.id in selection.ids },
            onSelectAll = { all -> selection.selectAll(list, all) },
            onDelete = onRequestDelete,
            onExit = selection::exit,
        )
    } else {
        TabsBar(
            tab,
            onTabChange,
            showMultiSelect = list.isNotEmpty(),
            onEnterSelect = selection::enter,
        )
    }
    if (list.isEmpty()) {
        EmptyState(
            message = if (tab == 0) "暂无记录，去地图选一个位置吧" else "暂无收藏",
            actionText = "添加点位",
            onAction = onAddPoint,
            modifier = Modifier.weight(1f),
        )
    } else {
        PointList(list, selection, onUse, onLocate, onFavorite, onDelete)
    }
}

/** 常规模式顶栏：Tab 切换 + 多选入口 */
@Composable
private fun TabsBar(
    tab: Int,
    onTabChange: (Int) -> Unit,
    showMultiSelect: Boolean,
    onEnterSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
            Tab(selected = tab == 0, onClick = { onTabChange(0) }, text = { Text("历史") })
            Tab(selected = tab == 1, onClick = { onTabChange(1) }, text = { Text("收藏") })
        }
        if (showMultiSelect) {
            IconButton(onClick = onEnterSelect) {
                Icon(Icons.Default.Checklist, contentDescription = "多选删除")
            }
        }
    }
}

/** 多选模式顶栏：退出、已选计数、全选切换、删除 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Default.Close, contentDescription = "退出多选")
        }
        Text(
            "已选 $selectedCount 项",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSelectAll(!allSelected) }) {
            Text(if (allSelected) "取消全选" else "全选")
        }
        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ColumnScope.PointList(
    list: List<SavedPoint>,
    selection: HomeSelectionState,
    onUse: (SavedPoint) -> Unit,
    onLocate: (SavedPoint) -> Unit,
    onFavorite: (SavedPoint) -> Unit,
    onDelete: (SavedPoint) -> Unit,
) {
    LazyColumn(Modifier.weight(1f)) {
        items(list, key = { it.id }) { item ->
            PointRow(
                item = item,
                selecting = selection.selecting,
                checked = item.id in selection.ids,
                onToggleSelect = { selection.toggle(item.id) },
                onUse = { onUse(item) },
                onLocate = { onLocate(item) },
                onFavorite = { onFavorite(item) },
                onDelete = { onDelete(item) },
            )
        }
    }
}

@Composable
private fun PointRow(
    item: SavedPoint,
    selecting: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onUse: () -> Unit,
    onLocate: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val coordText = String.format(Locale.US, "%.6f, %.6f", item.wgsLat, item.wgsLng)
    ListItem(
        headlineContent = {
            Text(
                item.name.ifBlank { "未命名位置" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // 副标题仅经纬度，点击复制
        supportingContent = {
            Text(
                coordText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { copyToClipboard(context, coordText) },
            )
        },
        leadingContent = {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onToggleSelect() })
            } else {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            if (!selecting) RowActions(item.isFavorite, onLocate, onFavorite, onDelete)
        },
        modifier = Modifier.clickable { if (selecting) onToggleSelect() else onUse() },
    )
}

/** 非多选模式的行尾操作：地图定位、收藏切换、删除 */
@Composable
private fun RowActions(
    isFavorite: Boolean,
    onLocate: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row {
        IconButton(onClick = onLocate) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = "地图定位",
                tint = LOCATE_TINT,
            )
        }
        FavoriteButton(isFavorite, onFavorite)
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除")
        }
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("coordinates", text))
    Toast.makeText(context, "已复制经纬度", Toast.LENGTH_SHORT).show()
}

@Composable
private fun FavoriteButton(
    isFavorite: Boolean,
    onFavorite: () -> Unit,
) {
    IconButton(onClick = onFavorite) {
        val starIcon =
            if (isFavorite) {
                Icons.Default.Favorite
            } else {
                Icons.Default.FavoriteBorder
            }
        Icon(
            starIcon,
            contentDescription = "收藏",
            tint = if (isFavorite) FAVORITE_TINT else LocalContentColor.current,
        )
    }
}

private val FAVORITE_TINT = Color(0xFFE91E63)

/** 列表条目「地图定位」按钮：红色，与收藏/删除区分 */
private val LOCATE_TINT = Color(0xFFF44336)
