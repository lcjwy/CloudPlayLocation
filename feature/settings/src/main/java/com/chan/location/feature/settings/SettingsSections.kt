package com.chan.location.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chan.location.core.common.MapSource
import com.chan.location.core.common.MockCheck
import com.chan.location.core.common.SystemIntents
import com.chan.location.core.ui.component.OnResumeEffect
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun MapSourceSection(
    current: MapSource,
    privacyAgreed: Boolean?,
    onSelect: (MapSource) -> Unit,
    onPrivacyRequired: () -> Unit,
) {
    SectionTitle("地图源")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = current == MapSource.BAIDU,
            onClick = {
                if (privacyAgreed != true) onPrivacyRequired() else onSelect(MapSource.BAIDU)
            },
            label = { Text("百度地图") },
        )
        FilterChip(
            selected = current == MapSource.OSM,
            onClick = { onSelect(MapSource.OSM) },
            label = { Text("开源地图 (OSM)") },
        )
    }
    Tip("百度源需同意隐私政策且已配置有效 Key；x86_64 模拟器请使用开源地图")
}

@Composable
internal fun IntervalSection(
    interval: Int,
    onChange: (Int) -> Unit,
) {
    SectionTitle("注入频率")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            String.format(Locale.US, "%d ms", interval),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text =
                when {
                    interval <= 30 -> "高刷新"
                    interval <= 60 -> "均衡"
                    else -> "省电"
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = interval.toFloat(),
        onValueChange = { value ->
            onChange(((value / 10).roundToInt() * 10).coerceIn(10, 100))
        },
        valueRange = 10f..100f,
        steps = 8,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Tip("间隔越小定位刷新越快，耗电也越高")
}

@Composable
internal fun FloatingSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("悬浮窗按钮", style = MaterialTheme.typography.bodyLarge)
            Tip("长按悬浮按钮 2 秒切换虚拟位置，可全局拖动")
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** WiFi 闪回防护：自持状态展示，引导关闭系统扫描类开关（无需断开 WiFi） */
@Composable
internal fun WifiSection() {
    val context = LocalContext.current
    var wifiOn by remember { mutableStateOf(MockCheck.isWifiEnabled(context)) }
    var wifiScanOn by remember { mutableStateOf(MockCheck.isWifiScanAlwaysAvailable(context)) }
    OnResumeEffect {
        wifiOn = MockCheck.isWifiEnabled(context)
        wifiScanOn = MockCheck.isWifiScanAlwaysAvailable(context)
    }

    HorizontalDivider()
    SectionTitle("WiFi 闪回防护")
    val wifiStatus =
        "WLAN ${if (wifiOn) "已开启" else "已关闭"} · " +
            "Wi-Fi 扫描 ${if (wifiScanOn) "已开启" else "已关闭"}"
    SettingItem(
        title = "Wi-Fi 扫描设置",
        subtitle = wifiStatus,
        onClick = {
            SystemIntents.start(
                context,
                SystemIntents.wifiScanningSettings(),
                fallback = SystemIntents.locationSource(),
            )
        },
    )
    Tip("系统可能根据 WiFi 扫描算出真实位置，覆盖虚拟位置（“位置闪回”）")
    Tip("建议在「位置信息」中关闭「Wi-Fi 扫描」；部分机型还有「Google 定位精确度」或厂商“提高定位精度”选项，同样关闭。无需断开 WiFi 联网")
}

@Composable
internal fun AboutSection(
    privacy: Boolean?,
    onShowPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val versionName =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0"
        }

    HorizontalDivider()
    SettingItem(
        title = "隐私政策",
        subtitle =
            when (privacy) {
                null -> "未选择"
                true -> "已同意"
                false -> "不同意"
            },
        onClick = onShowPrivacy,
    )
    HorizontalDivider()
    SettingItem(
        title = "开发者选项",
        subtitle = "前往设置“模拟位置信息应用”",
        onClick = { SystemIntents.start(context, SystemIntents.developerOptions()) },
    )
    HorizontalDivider()
    SettingItem(
        title = "关于",
        subtitle = "云游 v$versionName · 注入坐标为 WGS84，百度显示自动转 BD09",
        onClick = {},
    )
    HorizontalDivider()
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Tip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
