package com.chan.location.service.mock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.chan.location.core.common.MockCheck
import com.chan.location.core.common.MockCheckError
import com.chan.location.core.data.SettingsRepository
import com.chan.location.core.data.model.SelectedPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 虚拟位置启停控制器（进程级单例）。
 * 以 (悬浮窗开关, 运行开关, 注入频率) 的组合为唯一事实源，集中启停/更新服务；
 * UI 与悬浮窗均只调 start/stop/retarget，不再自行操作 Service。
 */
object MockLocationManager {
    private lateinit var appCtx: Context
    private lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var current: SelectedPoint? = null

    val running: Flow<Boolean> get() = settings.mockEnabled

    fun init(
        app: Application,
        settingsRepository: SettingsRepository,
    ) {
        if (::appCtx.isInitialized) return
        appCtx = app
        settings = settingsRepository
        reconcileOnChange()
        scope.launch {
            // 进程被杀后前台服务不会复活，进程重启时校正残留的启用状态
            if (settings.mockEnabled.first() && !MockLocationService.isAlive) {
                settings.setMockEnabled(false)
            }
        }
    }

    private fun reconcileOnChange() {
        scope.launch {
            combine(
                settings.floatingEnabled,
                settings.mockEnabled,
                settings.intervalMs,
            ) { f, r, i ->
                Triple(f, r, i)
            }.distinctUntilChanged().collect { (floating, running, interval) ->
                if (running && current != null) {
                    startServiceSafe(MockLocationService.intent(appCtx, current, interval))
                    if (floating && Settings.canDrawOverlays(appCtx)) {
                        startServiceSafe(Intent(appCtx, FloatingControlService::class.java))
                    } else {
                        appCtx.stopService(Intent(appCtx, FloatingControlService::class.java))
                    }
                } else {
                    appCtx.stopService(Intent(appCtx, MockLocationService::class.java))
                    appCtx.stopService(Intent(appCtx, FloatingControlService::class.java))
                }
            }
        }
    }

    private fun startServiceSafe(intent: Intent) {
        try {
            appCtx.startForegroundService(intent)
        } catch (ignore: IllegalStateException) {
            // 后台启动 FGS 被系统限制：回滚开关并提示（scope 在 Default 线程，Toast 需主线程）
            scope.launch { settings.setMockEnabled(false) }
            mainHandler.post {
                Toast
                    .makeText(appCtx, R.string.mock_start_failed, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * 启动或换点：运行中直接重发 intent 换点；服务已死则直接重启。
     * 不能只写 mockEnabled=true：服务被系统杀死后开关残留 true，
     * 值无变化会被 distinctUntilChanged 吞掉、recollect 永不触发。
     * 调用方需先通过 MockCheck 校验。
     */
    suspend fun start(point: SelectedPoint) {
        current = point
        settings.setSelectedPoint(point)
        val intent = MockLocationService.intent(appCtx, point, settings.intervalMs.first())
        if (MockLocationService.isAlive) {
            appCtx.startService(intent)
        } else {
            startServiceSafe(intent)
            settings.setMockEnabled(true)
        }
    }

    fun stop() {
        scope.launch { settings.setMockEnabled(false) }
    }

    /** 悬浮窗长按入口（无 UI 环境校验）。返回错误文案；null 表示成功 */
    fun tryToggleFromOverlay(): String? {
        if (!::appCtx.isInitialized) return "应用尚未初始化"
        return when {
            MockLocationService.isAlive -> {
                stop()
                null
            }

            else -> {
                val point = current
                when (val error = MockCheck.validate(appCtx, overlayRequired = false)) {
                    null ->
                        if (point == null) {
                            "请先在应用内选择位置"
                        } else {
                            scope.launch { start(point) }
                            null
                        }

                    else -> mockCheckMessage(error)
                }
            }
        }
    }

    private fun mockCheckMessage(error: MockCheckError): String =
        when (error) {
            MockCheckError.LOCATION_PERMISSION -> "请先授予定位权限"
            MockCheckError.MOCK_NOT_SELECTED -> "请先在开发者选项中选择本应用为模拟位置应用"
            MockCheckError.OVERLAY_PERMISSION -> "请先授予悬浮窗权限"
        }
}
