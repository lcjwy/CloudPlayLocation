package com.chan.location.service.mock

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
    private const val TAG = "MockLocationMgr"

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
        scope.launch {
            // 进程级 current 必须先于 reconcile 收集器恢复：初始发射若见到
            // mockEnabled=true 且 current=null 会走停服分支，误杀随后的恢复启动
            current = settings.selectedPoint.first()
            val wasRunning = settings.mockEnabled.first()
            // 清理被杀进程残留的 TestProvider（先清后恢复，避免误删新服务的注册）：
            // 进程死亡时 onDestroy 不会执行，残留会持续压制真实定位
            cleanupResidualProviders()
            reconcileOnChange()
            val point = current
            if (wasRunning && point != null) {
                // 进程被系统/ROM 回收（常见于夜间省电），前台服务随进程终止：
                // 用户打开应用即处于前台，直接恢复注入（悬停开关位不动）；
                // 启动失败由 startServiceSafe 回滚开关并提示
                val started =
                    startServiceSafe(
                        MockLocationService.intent(appCtx, point, settings.intervalMs.first()),
                    ) {
                        settings.setMockEnabled(false)
                    }
                if (started) {
                    mainHandler.post {
                        Toast.makeText(appCtx, R.string.mock_resumed, Toast.LENGTH_LONG).show()
                    }
                }
            } else if (wasRunning) {
                settings.setMockEnabled(false)
                mainHandler.post {
                    Toast.makeText(appCtx, R.string.mock_stopped_on_exit, Toast.LENGTH_LONG).show()
                }
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
                    startServiceSafe(MockLocationService.intent(appCtx, current, interval)) {
                        settings.setMockEnabled(false)
                    }
                    if (floating && Settings.canDrawOverlays(appCtx)) {
                        startServiceSafe(
                            Intent(appCtx, FloatingControlService::class.java),
                            R.string.float_start_failed,
                        ) {
                            settings.setFloatingEnabled(false)
                        }
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

    /** 启动 FGS；失败时回滚对应开关并提示——哪个服务失败回滚哪个开关、提示哪个服务，
     *  悬浮窗失败不能误关虚拟位置（scope 在 Default 线程，Toast 需主线程）。
     *  返回是否启动成功，调用方据此决定是否写运行开关。
     *  异常必须全部接住：厂商限制抛出的类型不一（ForegroundServiceStartNotAllowed/
     *  SecurityException 等），外漏会杀死 reconcile 收集器，之后所有启停静默失效 */
    @Suppress("TooGenericExceptionCaught")
    private fun startServiceSafe(
        intent: Intent,
        toastRes: Int = R.string.mock_start_failed,
        rollback: suspend () -> Unit,
    ): Boolean =
        try {
            appCtx.startForegroundService(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed", e)
            scope.launch { rollback() }
            mainHandler.post {
                Toast.makeText(appCtx, toastRes, Toast.LENGTH_LONG).show()
            }
            false
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
            try {
                appCtx.startService(intent)
            } catch (ignore: IllegalStateException) {
                // 后台态裸 startService 会被系统拒绝：回落到前台服务安全启动（含失败提示）
                startServiceSafe(intent) { settings.setMockEnabled(false) }
            }
        } else {
            // 启动失败时 startServiceSafe 已回滚开关，不能再写 true 盖掉回滚
            val started = startServiceSafe(intent) { settings.setMockEnabled(false) }
            if (started) settings.setMockEnabled(true)
        }
    }

    /** 停止：直接停两个服务并兜底清理 TestProvider，不依赖 reconcile 收集器存活，
     *  也不依赖 onDestroy 的清理成功——系统位置关闭时部分 ROM 注销 provider 会抛
     *  类型不一的异常，任何一环中断都会留下"关不掉"的虚拟位置 */
    fun stop() {
        scope.launch {
            settings.setMockEnabled(false)
            appCtx.stopService(Intent(appCtx, MockLocationService::class.java))
            appCtx.stopService(Intent(appCtx, FloatingControlService::class.java))
            cleanupResidualProviders()
        }
    }

    /** 清理可能残留的 TestProvider（未注册时抛 IAE，由 removeAll 自行吞掉） */
    private fun cleanupResidualProviders() {
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        TestProviders(lm).removeAll()
    }

    /** 服务内 TestProvider 注册失败（模拟位置应用被取消选择等）：
     *  服务会自行 stopSelf，这里回滚开关使 UI 与实际一致，不留假运行态 */
    fun onMockProvidersFailed() {
        scope.launch { settings.setMockEnabled(false) }
    }

    /** 悬浮窗服务内部启动失败（startForeground/加窗被拒）：
     *  服务会自行 stopSelf，这里回滚悬浮窗开关使 UI 与实际一致 */
    fun onFloatingServiceDead() {
        scope.launch { settings.setFloatingEnabled(false) }
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
            // 部分 ROM 系统位置关闭时探测即失败（应用其实已被选择）：先给位置开关线索
            MockCheckError.MOCK_NOT_SELECTED ->
                if (MockCheck.isLocationEnabled(appCtx)) {
                    "请先在开发者选项中选择本应用为模拟位置应用"
                } else {
                    "请先开启系统位置后重试；若仍失败，请在开发者选项中选择本应用"
                }
            MockCheckError.OVERLAY_PERMISSION -> "请先授予悬浮窗权限"
        }
}
