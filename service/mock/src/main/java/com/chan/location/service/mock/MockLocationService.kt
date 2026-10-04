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
import com.chan.location.core.data.model.SelectedPoint
import java.util.Locale

/**
 * 虚拟位置前台服务：注册 GPS/Network 双 TestProvider，按设置频率循环注入 WGS84 坐标。
 * 运行中换点/改频率直接重发 intent 即可（onStartCommand 更新后重排循环）。
 */
class MockLocationService : Service() {
    private lateinit var lm: LocationManager

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

    private var lastNotifiedLat = Double.NaN
    private var lastNotifiedLng = Double.NaN

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
                handler?.removeCallbacks(tick)
                handler?.post(tick)
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
        readIntent(intent)
        // 不能用坐标非零判断目标存在：(0,0) 是合法可输入坐标（几内亚湾）
        if (hasTarget) {
            if (startForegroundSafely()) {
                // 注册失败不在这里终止：部分 ROM 在系统位置关闭期间会拒绝 addTestProvider，
                // 且存在探测通过后注册瞬时失败的竞态。循环内按 1s 重试（期间开启系统位置
                // 或恢复选择后自动恢复注入），连续超限才回滚停服（见 attemptRegister）
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

    private fun readIntent(intent: Intent?) {
        intent?.let {
            hasTarget = it.getBooleanExtra(EXTRA_HAS_TARGET, hasTarget)
            lat = it.getDoubleExtra(EXTRA_LAT, lat)
            lng = it.getDoubleExtra(EXTRA_LNG, lng)
            intervalMs =
                it
                    .getIntExtra(EXTRA_INTERVAL, intervalMs)
                    .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        }
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
            Toast.makeText(this, R.string.mock_start_failed, Toast.LENGTH_LONG).show()
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureProviders(): Boolean {
        if (providersAdded) return true
        providersAdded =
            try {
                TestProviders(lm).addAll()
            } catch (e: Exception) {
                // ROM 差异大：SecurityException / IllegalArgumentException / 其它
                // 均有出现（尤其系统位置关闭时），统一按注册失败处理，由重试机制兜底
                Log.w(TAG, "addTestProvider failed", e)
                false
            }
        return providersAdded
    }

    private fun scheduleLoop() {
        if (handlerThread == null) {
            val thread = HandlerThread("MockLocation", Process.THREAD_PRIORITY_FOREGROUND)
            thread.start()
            handlerThread = thread
            handler = Handler(thread.looper)
        }
        handler?.removeCallbacks(tick)
        handler?.post(tick)
    }

    private val tick: Runnable =
        Runnable {
            if (providersAdded) {
                injectAll()
            } else {
                regRetry.attempt { ensureProviders() }
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
        if (lat == lastNotifiedLat && lng == lastNotifiedLng) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            lastNotifiedLat = lat
            lastNotifiedLng = lng
        } catch (ignore: SecurityException) {
            // 无通知权限时静默
        }
    }

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
        } catch (e: SecurityException) {
            // 部分ROM在系统位置关闭等场景抛 SecurityException：容忍并继续重试
            // （系统位置开启后注入自动恢复）；提示与日志整个服务周期仅一次
            if (!apiErrorShown) {
                Log.w(TAG, "setTestProviderLocation($provider) failed", e)
                apiErrorShown = true
                Toast.makeText(this, R.string.mock_api_error, Toast.LENGTH_LONG).show()
            }
        } catch (ignore: IllegalArgumentException) {
            // provider 未注册，跳过本次
        }
    }

    private fun buildNotification(): Notification =
        ServiceNotifications.build(
            this,
            CHANNEL_ID,
            R.string.mock_notification_title,
            String.format(Locale.US, "%.6f, %.6f", lat, lng),
        )

    override fun onDestroy() {
        isAlive = false
        unregisterReceiver(screenStateReceiver)
        handler?.removeCallbacks(tick)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        if (providersAdded) {
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

/** 双 TestProvider 注册/注销：API 31+ 用 ProviderProperties，26–30 用 Criteria */
private class TestProviders(
    private val lm: LocationManager,
) {
    /** 注销残留 provider；未注册过/未授权均属可忽略场景 */
    fun removeAll() {
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.removeTestProvider(provider)
            } catch (ignore: SecurityException) {
            } catch (ignore: IllegalArgumentException) {
            }
        }
    }

    fun addAll(): Boolean {
        removeAll()
        addGps()
        addNetwork()
        enableIfDisabled(LocationManager.GPS_PROVIDER)
        enableIfDisabled(LocationManager.NETWORK_PROVIDER)
        return true
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
 * 连续 maxFailures 次失败回调终止——瞬时失败（系统位置关闭/mock 选择竞态）
 * 在条件恢复后自动通过，持续失败才回滚，避免"开关开了又弹回"。
 */
private class RegistrationRetry(
    private val maxFailures: Int,
    private val retryIntervalMs: Long,
    private val onFirstFailure: () -> Unit,
    private val onGiveUp: () -> Unit,
) {
    private var failures = 0
    private var lastAttemptAt = 0L
    private var errorShown = false

    /** 返回 true 表示注册成功 */
    fun attempt(register: () -> Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
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

    private fun onRegisterFailed() {
        failures++
        if (!errorShown) {
            errorShown = true
            onFirstFailure()
        }
        if (failures >= maxFailures) onGiveUp()
    }
}
