package com.chan.location.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chan.location.core.common.MapSource
import com.chan.location.core.common.SystemIntents
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.ui.component.OnResumeEffect
import com.chan.location.core.ui.component.PrivacyPolicyDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val mapSource by settingsRepository.mapSource.collectAsStateWithLifecycle(MapSource.BAIDU)
    val interval by settingsRepository.intervalMs.collectAsStateWithLifecycle(100)
    val floating by settingsRepository.floatingEnabled.collectAsStateWithLifecycle(false)
    val privacy by settingsRepository.privacyAgreed.collectAsStateWithLifecycle(null)
    val baiduKey by settingsRepository.baiduKey.collectAsStateWithLifecycle("")
    val controller = remember { SettingsController(scope, settingsRepository) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        SettingsContent(
            controller = controller,
            mapSource = mapSource,
            interval = interval,
            floating = floating,
            privacy = privacy,
            baiduKey = baiduKey,
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

/** 设置页主体：各设置分区按序排列，含隐私弹窗与悬浮窗授权流转 */
@Composable
private fun SettingsContent(
    controller: SettingsController,
    mapSource: MapSource,
    interval: Int,
    floating: Boolean,
    privacy: Boolean?,
    baiduKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var pendingOverlayGrant by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        MapSourceSection(
            current = mapSource,
            privacyAgreed = privacy,
            onSelect = controller::selectMapSource,
            onPrivacyRequired = { showPrivacy = true },
        )
        IntervalSection(interval, onChange = controller::setInterval)
        BaiduKeySection(current = baiduKey, onSave = controller::setBaiduKey)
        FloatingSection(floating) { want ->
            if (want && !Settings.canDrawOverlays(context)) {
                pendingOverlayGrant = true
                SystemIntents.start(context, SystemIntents.overlayPermission(context))
            } else {
                controller.setFloating(want)
            }
        }
        WifiSection()
        AboutSection(privacy) { showPrivacy = true }
        Spacer(Modifier.height(24.dp))
    }

    PrivacyDialogIfShown(showPrivacy, controller) { showPrivacy = false }

    // 从悬浮窗授权页返回后自动完成开关
    OnResumeEffect {
        if (pendingOverlayGrant) {
            pendingOverlayGrant = false
            if (Settings.canDrawOverlays(context)) controller.setFloating(true)
        }
    }
}

@Composable
private fun PrivacyDialogIfShown(
    show: Boolean,
    controller: SettingsController,
    onDismiss: () -> Unit,
) {
    if (show) {
        PrivacyPolicyDialog(
            onAgree = {
                controller.setPrivacyAgreed(true)
                onDismiss()
            },
            onDecline = {
                controller.setPrivacyAgreed(false)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** 设置页操作集合：写 DataStore 统一经 scope 串行 */
private class SettingsController(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
) {
    fun selectMapSource(source: MapSource) {
        scope.launch { repo.setMapSource(source) }
    }

    fun setInterval(ms: Int) {
        scope.launch { repo.setIntervalMs(ms) }
    }

    fun setBaiduKey(key: String) {
        scope.launch { repo.setBaiduKey(key) }
    }

    fun setFloating(enabled: Boolean) {
        scope.launch { repo.setFloatingEnabled(enabled) }
    }

    fun setPrivacyAgreed(agreed: Boolean) {
        scope.launch { repo.setPrivacyAgreed(agreed) }
    }
}

/** 当前签名证书 SHA1（点击复制）：百度控制台给 Key 绑定"发布版安全码"用 */
@Composable
internal fun Sha1Row() {
    val context = LocalContext.current
    val sha1 = remember { signingSha1Hex(context) } ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    Text(
        text = "签名 SHA1：$sha1（点击复制，控制台绑定用，包名 ${context.packageName}）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("sha1", sha1))
                    Toast.makeText(context, "已复制签名 SHA1", Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 读取当前安装包签名证书的 SHA1 十六进制（冒号分隔大写）；读取失败返回 null。
 *  供设置页展示，用户在百度控制台给 Key 绑定"发布版安全码"时填写 */
internal fun signingSha1Hex(context: Context): String? =
    runCatching {
        val pm = context.packageManager
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)?.signatures
            }
        val cert = signatures?.firstOrNull() ?: return@runCatching null
        MessageDigest
            .getInstance("SHA-1")
            .digest(cert.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
