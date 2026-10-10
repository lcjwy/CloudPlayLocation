package com.chan.location.service.mock

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chan.location.core.common.MockCheck
import com.chan.location.core.data.model.SelectedPoint
import java.util.Locale

/**
 * 虚拟位置前台服务：注册 GPS/Network 双 TestProvider，按设置频率循环注入 WGS84 坐标。
 * 运行中换点/改频率直接重发 intent 即可（onStartCommand 更新后重排循环）。
 */
class MockLocationService : Service() {
    private lateinit var lm: LocationManager
    private val providerLock = Any()
    private var stopping = false

    // 主线程写（onCreate/onDestroy），注入线程与广播读：volatile 保证销毁空值对 tick 可见
    @Volatile
    private var handlerThread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    private var lat = 0.0
    private var lng = 0.0

    /** intent 是否携带有效目标点（START_NOT_STICKY 空重启无此标记） */
    private var hasTarget = false

    @Volatile
    private var intervalMs = DEFAULT_INTERVAL_MS

    // 主线程（onStartCommand）与注入线程（attemptRegister）共同读写
    @Volatile
    private var providersAdded = false

    /** fused provider 是否已注册（仅 GMS 设备存在该 provider，注册失败可容忍） */
    @Volatile
    private var fusedSupported = false

    /** 未落地提示是否已发（整个服务周期一次，防刷屏） */
    @Volatile
    private var ineffectiveShown = false

    /** 落地守卫恢复状态：上次恢复动作时刻与连续恢复次数（仅注入线程访问） */
    private var lastRecoveryAt = 0L

    private var recoveryCount = 0

    /** 服务自身 GPS 订阅收到的最近投递（落地校验真值来源） */
    @Volatile
    private var lastDelivered: Location? = null

    @Volatile
    private var verifyListening = false

    /** 全量覆写四个回调：API 26–29 的 LocationListener 其余方法非 default，
     *  SAM 只实现 onLocationChanged 会在老设备上 AbstractMethodError */
    private val deliveryListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastDelivered = location
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {
            }

            @Deprecated("Deprecated in Java")
            override fun onProviderEnabled(provider: String) {
            }

            @Deprecated("Deprecated in Java")
            override fun onProviderDisabled(provider: String) {
            }
        }

    private var lastNotifiedLat = Double.NaN
    private var lastNotifiedLng = Double.NaN

    /** 通知是否处于"等待系统位置"文案态：仅在翻转时更新，避免每秒刷通知 */
    private var lastNotifiedWaiting = false

    /** 注册重试状态机：1s 节奏，首次失败提示一次，连续约 15s 失败回滚开关并停服 */
    private val regRetry =
        RegistrationRetry(
            maxFailures = REG_MAX_FAILURES,
            retryIntervalMs = REG_RETRY_INTERVAL_MS,
            onFirstFailure = {
                Toast.makeText(this, R.string.mock_no_permission, Toast.LENGTH_SHORT).show()
            },
            onGiveUp = {
                MockLocationManager.onMockProvidersFailed()
                stopSelf()
            },
        )

    /** 注入 API 异常提示/日志闸门：整个服务周期只提示一次（成功不复位，
     *  GPS/Network 单边失败交替时避免每个注入周期都 Toast 刷屏） */
    @Volatile
    private var apiErrorShown = false

    /** 息屏后降低注入频率（耗电优化）；亮屏立即恢复设定值 */
    private var screenOn = true

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                // 经 scheduleLoop 重排：顺带完成死线程重建
                scheduleLoop()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        screenOn =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ServiceNotifications.createChannel(
            this,
            CHANNEL_ID,
            R.string.mock_notification_channel,
            NotificationManager.IMPORTANCE_LOW,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // intent 必带完整字段（见 intent()）；START_NOT_STICKY 空重启时 intent 为
        // null，沿用旧值即可——hasTarget 标记保证无目标不空转
        intent?.let {
            hasTarget = it.getBooleanExtra(EXTRA_HAS_TARGET, hasTarget)
            lat = it.getDoubleExtra(EXTRA_LAT, lat)
            lng = it.getDoubleExtra(EXTRA_LNG, lng)
            intervalMs =
                it
                    .getIntExtra(EXTRA_INTERVAL, intervalMs)
                    .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        }
        // 不能用坐标非零判断目标存在：(0,0) 是合法可输入坐标（几内亚湾）
        if (hasTarget) {
            if (startForegroundSafely()) {
                // 注册失败不在这里终止：部分 ROM 在系统位置关闭期间会拒绝 addTestProvider，
                // 且存在探测通过后注册瞬时失败的竞态。循环内按 1s 重试（期间开启系统位置
                // 或恢复选择后自动恢复注入）；系统位置关闭期间不计失败，仅系统位置开启下
                // 连续超限才回滚停服（见 RegistrationRetry）
                scheduleLoop()
            } else {
                // startForeground 失败必须立即停止：经 startForegroundService 拉起的服务
                // 若 5s 内未成功 startForeground，系统会抛异常杀死进程
                stopSelf()
            }
        } else {
            // 无有效目标点（多为 START_NOT_STICKY 场景），拒绝空转
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** FGS 启动受系统/厂商限制，异常类型多，统一按启动失败处理 */
    @Suppress("TooGenericExceptionCaught")
    private fun startForegroundSafely(): Boolean =
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            lastNotifiedLat = lat
            lastNotifiedLng = lng
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            // 管理器侧 startForegroundService 已成功返回并写了运行开关：
            // 服务内自杀前必须回滚，否则开关 ON 而服务已死
            MockLocationManager.onMockProvidersFailed()
            Toast.makeText(this, R.string.mock_start_failed, Toast.LENGTH_LONG).show()
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureProviders(): Boolean =
        synchronized(providerLock) {
            if (stopping) return@synchronized false
            if (providersAdded) return@synchronized true
            val tp = TestProviders(lm)
            providersAdded =
                try {
                    tp.addAll()
                } catch (e: Exception) {
                    // ROM 差异大：SecurityException / IllegalArgumentException / 其它
                    // 均有出现（尤其系统位置关闭时），统一按注册失败处理，由重试机制兜底
                    Log.w(TAG, "addTestProvider failed", e)
                    false
                }
            fusedSupported = providersAdded && tp.fusedRegistered
            if (providersAdded) {
                // 注册即给守卫 1s 观察期：新 provider 首次投递需要一点时间，
                // 避免启动瞬间误触发恢复动作
                lastRecoveryAt = SystemClock.elapsedRealtime()
            }
            providersAdded
        }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleLoop() {
        // 线程可能已被历史异常杀死：looper 未 quit 时 post 仍返回成功入队，
        // 但永远无人消费（服务存活、开关显示运行中，却永不注入，直到进程重启）。
        // 检测并重建，保证换点/重发 intent 后注入可恢复
        if (handlerThread?.isAlive != true) {
            handlerThread = null
            handler = null
        }
        if (handlerThread == null) {
            val thread = HandlerThread("MockLocation", Process.THREAD_PRIORITY_FOREGROUND)
            thread.start()
            handlerThread = thread
            handler = Handler(thread.looper)
        }
        // 服务自身常驻订阅 GPS 投递流：① 落地校验的真值来源（lastKnown 在部分
        // ROM 不可靠）；② 很多 ROM 会对"无活跃消费者"的 provider 休眠停摆——
        // 常驻订阅保证替身 provider 永远有消费者，不被熄火
        if (!verifyListening && handler != null) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    deliveryListener,
                    handler!!.looper,
                )
                verifyListening = true
            } catch (e: Exception) {
                // ROM 差异：注册订阅失败不致命，落地校验退化为 null→走修复路径
                Log.w(TAG, "verify listener register failed", e)
            }
        }
        handler?.removeCallbacks(tick)
        handler?.post(tick)
    }

    /** tick 整体兜底：任何异常都不能杀死注入线程——末尾的重新调度必须必然执行 */
    @Suppress("TooGenericExceptionCaught")
    private val tick: Runnable =
        Runnable {
            try {
                if (providersAdded) {
                    injectAll()
                } else {
                    val locationOff = !MockCheck.isLocationEnabled(this)
                    if (locationOff) {
                        // 系统位置关闭是可逆操作（用户省电习惯），与模拟应用被取消选择不同：
                        // 不计失败不放弃——若在此放弃停服，位置重开时真实定位直接暴露，
                        // 表现为"跳位置"。重开后下一轮 attempt 自动恢复注册与注入
                        regRetry.reset()
                    } else {
                        regRetry.attempt { ensureProviders() }
                    }
                    // 等待期间通知改示等待文案（不误导为注入中）；翻转时才更新
                    if (locationOff != lastNotifiedWaiting) {
                        lastNotifiedWaiting = locationOff
                        try {
                            NotificationManagerCompat.from(this).notify(
                                NOTIFICATION_ID,
                                buildNotification(waiting = locationOff),
                            )
                        } catch (ignore: SecurityException) {
                            // 无通知权限时静默
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            // 亮屏用设定频率；息屏钳制到 ≥1s，大幅减少唤醒与 IPC 次数
            val delay =
                if (screenOn) {
                    intervalMs.toLong()
                } else {
                    maxOf(intervalMs.toLong(), SCREEN_OFF_MIN_INTERVAL_MS)
                }
            handler?.postDelayed(tick, delay)
        }

    private fun injectAll() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        injectOne(LocationManager.NETWORK_PROVIDER, NETWORK_ACCURACY, now, elapsed)
        injectOne(LocationManager.GPS_PROVIDER, GPS_ACCURACY, now, elapsed)
        if (fusedSupported) injectOne(LocationManager.FUSED_PROVIDER, GPS_ACCURACY, now, elapsed)
        // 落地守卫：每 tick 只读校验（volatile 比对，零 binder 成本），未落地才按
        // 节流介入。不设固定周期——周期检查既是失效暴露窗口，批量翻转也会与 ROM
        // 的架空互相激化。恢复两级：首次 kick（翻转+事件唤醒），持续失败升级重注册
        if (MockCheck.isLocationEnabled(this) && !injectionLandedNow()) {
            val now = SystemClock.elapsedRealtime()
            val throttle =
                if (recoveryCount <= 1) RECOVERY_THROTTLE_FAST_MS else RECOVERY_THROTTLE_SLOW_MS
            if (now - lastRecoveryAt >= throttle) {
                lastRecoveryAt = now
                recoveryCount++
                if (recoveryCount == 1) {
                    TestProviders(lm).kick()
                } else {
                    Log.w(TAG, "recovery: force re-register (attempt=$recoveryCount)")
                    providersAdded = false
                }
                if (recoveryCount == RECOVERY_TOAST_AFTER && !ineffectiveShown) {
                    ineffectiveShown = true
                    Toast.makeText(this, R.string.mock_not_effective, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (lat == lastNotifiedLat && lng == lastNotifiedLng) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            lastNotifiedLat = lat
            lastNotifiedLng = lng
        } catch (ignore: SecurityException) {
            // 无通知权限时静默
        }
    }

    /** 注入落地真值：自身 GPS 订阅近期是否收到 mock 标记的投递。lastKnown 在
     *  部分 ROM 不更新/被模糊化会误报；投递流是最直接的端到端证据 */
    private fun injectionLandedNow(): Boolean {
        val fix = lastDelivered ?: return false
        val stale =
            SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos > DELIVERY_STALE_NS
        val fromMock =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                fix.isMock
            } else {
                @Suppress("DEPRECATION")
                fix.isFromMockProvider
            }
        val landed = !stale && fromMock
        if (!landed) {
            Log.w(
                TAG,
                "injection not landing: stale=$stale fromMock=$fromMock " +
                    "fix=$fix target=($lat, $lng)",
            )
        }
        return landed
    }

    /** 注入异常必须全部吞掉：异常外漏会杀死注入线程→进程崩溃，已注册的
     *  TestProvider 残留且无人清理（表现为"虚拟位置关不掉"）；
     *  provider 丢失时将注册状态复位，由下一轮循环重建 */
    @Suppress("TooGenericExceptionCaught")
    private fun injectOne(
        provider: String,
        accuracy: Float,
        timeMs: Long,
        elapsedNanos: Long,
    ) {
        try {
            val location =
                Location(provider).apply {
                    latitude = this@MockLocationService.lat
                    longitude = this@MockLocationService.lng
                    this.accuracy = accuracy
                    altitude = ALTITUDE
                    speed = 0f
                    bearing = 0f
                    time = timeMs
                    elapsedRealtimeNanos = elapsedNanos
                    extras = Bundle().apply { putInt("satellites", SATELLITES) }
                }
            lm.setTestProviderLocation(provider, location)
        } catch (ignore: IllegalArgumentException) {
            // provider 丢失后重新注册；fused 注销只关 fused，不牵连主注册
            if (provider == LocationManager.FUSED_PROVIDER) {
                fusedSupported = false
            } else {
                providersAdded = false
            }
        } catch (e: Exception) {
            // 部分ROM在系统位置关闭等场景抛出类型不一的异常（不止 SecurityException）：
            // 容忍并继续（系统位置开启后注入自动恢复）；提示与日志整个服务周期仅一次
            if (!apiErrorShown) {
                Log.w(TAG, "setTestProviderLocation($provider) failed", e)
                apiErrorShown = true
                Toast.makeText(this, R.string.mock_api_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildNotification(waiting: Boolean = false): Notification =
        ServiceNotifications.build(
            this,
            CHANNEL_ID,
            titleRes =
                if (waiting) {
                    R.string.mock_notification_waiting_title
                } else {
                    R.string.mock_notification_title
                },
            contentText =
                if (waiting) {
                    getString(R.string.mock_waiting_location)
                } else {
                    String.format(Locale.US, "%.6f, %.6f", lat, lng)
                },
        )

    override fun onDestroy() {
        isAlive = false
        unregisterReceiver(screenStateReceiver)
        if (verifyListening) {
            runCatching { lm.removeUpdates(deliveryListener) }
            verifyListening = false
        }
        handler?.removeCallbacks(tick)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        synchronized(providerLock) {
            stopping = true
            TestProviders(lm).removeAll()
            providersAdded = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MockLocation"
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LNG = "extra_lng"
        private const val EXTRA_INTERVAL = "extra_interval"
        private const val EXTRA_HAS_TARGET = "extra_has_target"
        private const val MIN_INTERVAL_MS = 10
        private const val MAX_INTERVAL_MS = 100
        private const val DEFAULT_INTERVAL_MS = 100

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        private const val GPS_ACCURACY = 1f
        private const val NETWORK_ACCURACY = 50f
        private const val ALTITUDE = 55.0
        private const val SATELLITES = 7
        private const val SCREEN_OFF_MIN_INTERVAL_MS = 1000L

        /** 落地守卫恢复节流：前两次快速介入（1s），持续失败退避到 5s——
         *  防恢复动作与 ROM 的架空互相激化 */
        private const val RECOVERY_THROTTLE_FAST_MS = 1_000L
        private const val RECOVERY_THROTTLE_SLOW_MS = 5_000L

        /** 连续恢复达到该次数仍未落地 → Toast 提示（整个服务周期一次） */
        private const val RECOVERY_TOAST_AFTER = 10

        /** 投递流时效：超过 10s 无投递视为未落地（健康检查周期 5s 的两倍冗余） */
        private const val DELIVERY_STALE_NS = 10_000_000_000L

        /** 注册重试：1s 节奏，连续 15 次（约 15s）失败判定为持续失败 */
        private const val REG_RETRY_INTERVAL_MS = 1_000L
        private const val REG_MAX_FAILURES = 15

        @Volatile
        var isAlive = false
            private set

        fun intent(
            context: Context,
            point: SelectedPoint?,
            intervalMs: Int,
        ): Intent =
            Intent(context, MockLocationService::class.java)
                .putExtra(EXTRA_HAS_TARGET, point != null)
                .putExtra(EXTRA_LAT, point?.wgsLat ?: 0.0)
                .putExtra(EXTRA_LNG, point?.wgsLng ?: 0.0)
                .putExtra(EXTRA_INTERVAL, intervalMs)
    }
}

/** 双 TestProvider 注册/注销：API 31+ 用 ProviderProperties，26–30 用 Criteria；
 *  internal 供 MockLocationManager 启动时兜底清理进程残留 */
internal class TestProviders(
    private val lm: LocationManager,
) {
    private companion object {
        private const val TAG = "MockLocation"
    }

    /** fused provider 是否注册成功（尽力而为，不参与 addAll 成败判定） */
    var fusedRegistered = false
        private set

    /** 注销残留 provider；未注册/未授权，以及系统位置关闭时部分 ROM 抛出的
     *  异常类型不一，逐个 provider 独立吞掉——任一失败不能中断另一个的清理 */
    @Suppress("TooGenericExceptionCaught")
    fun removeAll() {
        for (provider in arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )) {
            try {
                lm.removeTestProvider(provider)
            } catch (ignore: Exception) {
            }
        }
    }

    fun addAll(): Boolean {
        removeAll()
        var registrationComplete = false
        try {
            addGps()
            addNetwork()
            enableIfDisabled(LocationManager.GPS_PROVIDER)
            enableIfDisabled(LocationManager.NETWORK_PROVIDER)
            // 启动时刻的可用性翻转：注册若发生在系统位置已开启时，provider 直接
            // 报告可用、无人收到状态变化事件——启动前已订阅并休眠的系统定位服务
            // （GMS 融合引擎等）不会重新订阅，表现为"有时没唤醒"。显式翻转踢醒
            kick()
            tryRegisterFused()
            registrationComplete = true
            return true
        } finally {
            // 注册未全部完成时回滚，避免 GPS 成功、Network 失败留下替身。
            if (!registrationComplete) removeAll()
        }
    }

    /** 双 provider disable→enable 翻转：产生可用性变化事件，复位启用态并唤醒
     *  休眠的定位订阅方 */
    @Suppress("TooGenericExceptionCaught")
    fun kick() {
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.setTestProviderEnabled(provider, false)
                lm.setTestProviderEnabled(provider, true)
            } catch (ignore: Exception) {
                // 部分 ROM 翻转异常：容忍，enableIfDisabled 已保证启用态
            }
        }
    }

    /** 校验双 provider 启用态并重新启用（调用方须保证系统位置已开启，否则恒判禁用）。
     *  返回 false 表示有 provider 已被系统注销，调用方应复位注册状态走重注册 */
    @Suppress("TooGenericExceptionCaught")
    fun reenableIfNeeded(): Boolean {
        var allRegistered = true
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(true)) continue
            try {
                lm.setTestProviderEnabled(provider, true)
            } catch (ignore: IllegalArgumentException) {
                allRegistered = false
            } catch (e: Exception) {
                // ROM 差异：本轮启用失败，下个校验周期重试；留痕供 ROM 拒启排查
                Log.w(TAG, "re-enable $provider failed", e)
            }
        }
        return allRegistered
    }

    /** 废弃的 10 参重载 + 常量（Gogogo 同款，含 API 31+）：Builder 新重载在部分 ROM 行为不一致 */
    @Suppress("DEPRECATION")
    private fun addGps() {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            true,
            false,
            false,
            true,
            true,
            true,
            if (modern) {
                ProviderProperties.POWER_USAGE_HIGH
            } else {
                android.location.Criteria.POWER_HIGH
            },
            if (modern) {
                ProviderProperties.ACCURACY_FINE
            } else {
                android.location.Criteria.ACCURACY_FINE
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun addNetwork() {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        lm.addTestProvider(
            LocationManager.NETWORK_PROVIDER,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            if (modern) {
                ProviderProperties.POWER_USAGE_LOW
            } else {
                android.location.Criteria.POWER_LOW
            },
            if (modern) {
                ProviderProperties.ACCURACY_COARSE
            } else {
                android.location.Criteria.ACCURACY_COARSE
            },
        )
    }

    /** GMS 融合定位旁路补丁：很多 App 走 FusedLocationProviderClient（fused），
     *  只替身 GPS/Network 拦不住它——表现为"部分应用仍显示真实位置"。fused 仅
     *  GMS 设备存在（无 GMS 的国产 ROM 无此 provider，注册即失败），失败可容忍、
     *  不参与 addAll 成败判定 */
    @Suppress("DEPRECATION")
    private fun tryRegisterFused() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        fusedRegistered =
            try {
                // addTestProvider 失败以异常表达（无返回值），调用成功即已注册
                lm.addTestProvider(
                    LocationManager.FUSED_PROVIDER,
                    true,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE,
                )
                true
            } catch (ignore: Exception) {
                false
            }
    }

    /** API 31+ 注册即启用；仅当系统仍报禁用时补一次显式启用（系统位置关闭常见）。
     *  启用调用在部分 ROM（尤其系统位置关闭时）会抛出类型不一的异常：一律容忍跳过——
     *  异常外漏会让整次注册被误判失败，主开关开启后 provider 随之启用，注入自动生效 */
    @Suppress("TooGenericExceptionCaught")
    private fun enableIfDisabled(provider: String) {
        if (!lm.isProviderEnabled(provider)) {
            try {
                lm.setTestProviderEnabled(provider, true)
            } catch (ignore: Exception) {
            }
        }
    }
}

/**
 * 注册重试状态机（仅注入线程访问）：按固定节奏重试，首次失败提示一次，
 * 连续 maxFailures 次失败回调终止——瞬时失败（mock 选择竞态）在条件恢复后
 * 自动通过，持续失败才回滚，避免"开关开了又弹回"；系统位置关闭期间由调用方
 * reset() 清零不计（可逆状态，永不触发放弃）。
 */
internal class RegistrationRetry(
    private val maxFailures: Int,
    private val retryIntervalMs: Long,
    private val onFirstFailure: () -> Unit,
    private val onGiveUp: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var failures = 0
    private var lastAttemptAt = 0L
    private var errorShown = false

    /** 返回 true 表示注册成功 */
    fun attempt(register: () -> Boolean): Boolean {
        val now = clock()
        if (now - lastAttemptAt < retryIntervalMs) return false
        lastAttemptAt = now
        val success = register()
        if (success) {
            failures = 0
        } else {
            onRegisterFailed()
        }
        return success
    }

    /** 清零失败计数但不尝试注册：用于系统位置关闭等可逆等待期 */
    fun reset() {
        failures = 0
    }

    private fun onRegisterFailed() {
        failures++
        if (!errorShown) {
            errorShown = true
            onFirstFailure()
        }
        if (failures >= maxFailures) onGiveUp()
    }
}
