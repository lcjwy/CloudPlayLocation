package com.chan.location.feature.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    fun setFloating(enabled: Boolean) {
        scope.launch { repo.setFloatingEnabled(enabled) }
    }

    fun setPrivacyAgreed(agreed: Boolean) {
        scope.launch { repo.setPrivacyAgreed(agreed) }
    }
}
