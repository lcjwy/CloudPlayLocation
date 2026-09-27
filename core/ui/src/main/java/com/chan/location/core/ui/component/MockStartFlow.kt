package com.chan.location.core.ui.component

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.chan.location.core.common.MockCheck
import com.chan.location.core.common.MockCheckError
import com.chan.location.core.common.SystemIntents

/** 校验失败引导：标题 + 说明 + 跳转目标 */
data class JumpDialogState(
    val title: String,
    val text: String,
    val intent: Intent,
)

/** 校验失败 → 引导弹窗内容；LOCATION_PERMISSION 由权限闸门先行处理，返回 null */
fun jumpDialogFor(
    error: MockCheckError,
    context: Context,
): JumpDialogState? =
    when (error) {
        MockCheckError.LOCATION_PERMISSION -> null
        MockCheckError.MOCK_NOT_SELECTED ->
            JumpDialogState(
                "未选择模拟位置应用",
                "请在系统开发者选项中将本应用设置为“模拟位置信息应用”。",
                SystemIntents.developerOptions(),
            )

        MockCheckError.GPS_DISABLED ->
            JumpDialogState(
                "GPS 未开启",
                "请在系统“位置信息”设置中开启定位。",
                SystemIntents.locationSource(),
            )

        MockCheckError.OVERLAY_PERMISSION ->
            JumpDialogState(
                "悬浮窗未授权",
                "开启悬浮窗按钮需要“显示在其他应用上层”权限。",
                SystemIntents.overlayPermission(context),
            )
    }

/** 位置权限闸门：已有权限直接执行，否则申请并在授权回调后续跑；拒绝时提示 */
@Composable
fun rememberPermissionGate(deniedMessage: String): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            val action = pendingAction
            pendingAction = null
            if (action != null) {
                if (grants.values.any { it }) {
                    action()
                } else {
                    showToast(context, deniedMessage)
                }
            }
        }
    return { action ->
        if (MockCheck.hasLocationPermission(context)) {
            action()
        } else {
            pendingAction = action
            launcher.launch(locationPermissions())
        }
    }
}

private fun showToast(
    context: Context,
    message: String,
) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun locationPermissions() =
    buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

/**
 * 启用虚拟位置的统一闸门：位置权限 → 模拟位置应用/GPS/悬浮窗校验 → WiFi 提醒。
 * 主页与地图页共用，消除各自重复的校验链与引导弹窗。
 */
class MockStartGate internal constructor(
    private val context: Context,
    private val overlayRequired: () -> Boolean,
    private val withPermissions: (action: () -> Unit) -> Unit,
    private val jump: MutableState<JumpDialogState?>,
    private val wifiWarn: MutableState<Boolean>,
    private val pendingStart: MutableState<(() -> Unit)?>,
) {
    /** 仅申请位置权限后执行（如进入地图选点页） */
    fun runWithPermissions(action: () -> Unit) = withPermissions(action)

    /** 校验链通过后回调 onReady；失败弹引导或 WiFi 提醒（“仍要继续”后续跑） */
    fun requestStart(onReady: () -> Unit) {
        withPermissions {
            when (val error = MockCheck.validate(context, overlayRequired = overlayRequired())) {
                null ->
                    if (MockCheck.isWifiEnabled(context)) {
                        pendingStart.value = onReady
                        wifiWarn.value = true
                    } else {
                        onReady()
                    }

                MockCheckError.LOCATION_PERMISSION -> Unit // 权限闸门已保证
                else -> jump.value = jumpDialogFor(error, context)
            }
        }
    }

    /** 在页面根部渲染引导弹窗与 WiFi 提醒 */
    @Composable
    fun Dialogs() {
        JumpGuideDialog(jump.value) { jump.value = null }
        if (wifiWarn.value) {
            WifiWarnDialog(
                onCloseWifi = {
                    wifiWarn.value = false
                    SystemIntents.start(context, SystemIntents.wifiSettings())
                },
                onProceed = {
                    wifiWarn.value = false
                    pendingStart.value?.invoke()
                    pendingStart.value = null
                },
            )
        }
    }
}

@Composable
fun rememberMockStartGate(
    overlayRequired: () -> Boolean,
    permissionDeniedMessage: String,
): MockStartGate {
    val context = LocalContext.current
    val jump = remember { mutableStateOf<JumpDialogState?>(null) }
    val wifiWarn = remember { mutableStateOf(false) }
    val pendingStart = remember { mutableStateOf<(() -> Unit)?>(null) }
    val withPermissions = rememberPermissionGate(permissionDeniedMessage)
    return remember(context) {
        MockStartGate(context, overlayRequired, withPermissions, jump, wifiWarn, pendingStart)
    }
}

/** 校验失败引导弹窗 */
@Composable
fun JumpGuideDialog(
    state: JumpDialogState?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    state?.let { target ->
        ConfirmDialog(
            title = target.title,
            text = target.text,
            confirmText = "去设置",
            onConfirm = {
                SystemIntents.start(context, target.intent)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** WiFi 闪回提醒：去关闭 WLAN（不阻断的软提醒） */
@Composable
fun WifiWarnDialog(
    onCloseWifi: () -> Unit,
    onProceed: () -> Unit,
) {
    ConfirmDialog(
        title = "检测到 WLAN 已开启",
        text = "系统可能基于 WiFi 扫描计算出真实位置，导致虚拟位置闪回。建议关闭 WLAN 后再使用。",
        confirmText = "去关闭",
        dismissText = "仍要继续",
        onConfirm = onCloseWifi,
        onDismiss = onProceed,
    )
}
