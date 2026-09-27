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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "虚拟定位",
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
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                            ?: "去地图选点，或点击右下角添加",
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
    onUse: (SavedPoint) -> Unit,
    onLocate: (SavedPoint) -> Unit,
    onAddPoint: () -> Unit,
    onFavorite: (SavedPoint) -> Unit,
    onDelete: (SavedPoint) -> Unit,
) {
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { onTabChange(0) }, text = { Text("历史") })
        Tab(selected = tab == 1, onClick = { onTabChange(1) }, text = { Text("收藏") })
    }
    val list = if (tab == 0) history else favorites
    if (list.isEmpty()) {
        EmptyState(
            message = if (tab == 0) "暂无记录，去地图选一个位置吧" else "暂无收藏",
            actionText = "添加点位",
            onAction = onAddPoint,
            modifier = Modifier.weight(1f),
        )
    } else {
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { item ->
                PointRow(
                    item = item,
                    onUse = { onUse(item) },
                    onLocate = { onLocate(item) },
                    onFavorite = { onFavorite(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@Composable
private fun PointRow(
    item: SavedPoint,
    onUse: () -> Unit,
    onLocate: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { PointSubtitle(item) },
        leadingContent = {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onLocate) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "地图定位",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                FavoriteButton(item.isFavorite, onFavorite)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onUse),
    )
}

/** 副标题两行：第一行经纬度（点击复制），第二行时间 */
@Composable
private fun PointSubtitle(item: SavedPoint) {
    val context = LocalContext.current
    val coordText = String.format(Locale.US, "%.6f, %.6f", item.wgsLat, item.wgsLng)
    Column {
        Text(
            coordText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable { copyToClipboard(context, coordText) },
        )
        Text(
            formatTime(item.lastUsedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
