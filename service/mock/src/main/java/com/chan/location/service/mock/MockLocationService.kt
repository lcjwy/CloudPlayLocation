package com.chan.location.service.mock

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/**
 * 虚拟位置前台服务：注册 GPS/Network 双 TestProvider，按设置频率循环注入 WGS84 坐标。
 * 运行中换点/改频率直接重发 intent 即可（onStartCommand 更新后重排循环）。
 */
class MockLocationService : Service() {
    private lateinit var lm: LocationManager
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var lat = 0.0
    private var lng = 0.0

    @Volatile
    private var intervalMs = DEFAULT_INTERVAL_MS

    private var providersAdded = false
    private var lastNotifiedLat = Double.NaN
    private var lastNotifiedLng = Double.NaN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        if (lat != 0.0 || lng != 0.0) {
            if (startForegroundSafely() && ensureProviders()) scheduleLoop()
        } else {
            // 无有效目标点（多为 START_NOT_STICKY 场景），拒绝空转
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun readIntent(intent: Intent?) {
        intent?.let {
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
        } catch (ignore: Exception) {
            false
        }

    private fun ensureProviders(): Boolean {
        if (providersAdded) return true
        providersAdded =
            try {
                TestProviders(lm).addAll()
            } catch (ignore: SecurityException) {
                Toast.makeText(this, R.string.mock_no_permission, Toast.LENGTH_SHORT).show()
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
            injectAll()
            handler?.postDelayed(tick, intervalMs.toLong())
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
        } catch (ignore: SecurityException) {
            // 模拟位置应用被取消选择：停止注入
            handler?.removeCallbacks(tick)
            stopSelf()
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
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_INTERVAL = "extra_interval"
        const val MIN_INTERVAL_MS = 10
        const val MAX_INTERVAL_MS = 100
        const val DEFAULT_INTERVAL_MS = 100

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        private const val GPS_ACCURACY = 1f
        private const val NETWORK_ACCURACY = 50f
        private const val ALTITUDE = 55.0
        private const val SATELLITES = 7

        @Volatile
        var isAlive = false
            private set

        fun intent(
            context: Context,
            point: com.chan.location.core.data.model.SelectedPoint?,
            intervalMs: Int,
        ): Intent =
            Intent(context, MockLocationService::class.java)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addModern()
        } else {
            addLegacy()
        }
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        return true
    }

    private fun addModern() {
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            ProviderProperties
                .Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build(),
        )
        lm.addTestProvider(
            LocationManager.NETWORK_PROVIDER,
            ProviderProperties
                .Builder()
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_COARSE)
                .build(),
        )
    }

    private fun addLegacy() {
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            true,
            false,
            false,
            true,
            true,
            false,
            Criteria.POWER_HIGH,
            Criteria.ACCURACY_FINE,
        )
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            LocationManager.NETWORK_PROVIDER,
            true,
            false,
            true,
            false,
            false,
            false,
            false,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_COARSE,
        )
    }
}
