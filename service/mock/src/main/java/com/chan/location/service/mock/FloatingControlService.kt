package com.chan.location.service.mock

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/** 悬浮按钮承载服务（specialUse 前台）。窗口本身无启停能力，启停统一回调 MockLocationManager */
class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var buttonView: FloatingButtonView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceNotifications.createChannel(
            this,
            CHANNEL_ID,
            R.string.float_notification_channel,
            NotificationManager.IMPORTANCE_MIN,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (startForegroundSafely()) showOverlay()
        // 生命周期完全由 MockLocationManager 按 (运行, 悬浮窗开关) 驱动；
        // STICKY 会在进程被杀后复活服务，与状态校正竞态时留下
        // 悬浮窗开关实际已关/虚拟位置已停的"幽灵按钮"，故不自动重启
        return START_NOT_STICKY
    }

    /** FGS 启动受系统/厂商限制，异常类型多，统一按启动失败处理 */
    @Suppress("TooGenericExceptionCaught")
    private fun startForegroundSafely(): Boolean =
        try {
            val notification =
                ServiceNotifications.build(
                    this,
                    CHANNEL_ID,
                    R.string.float_notification_title,
                    getString(R.string.float_notification_text),
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (ignore: Exception) {
            stopSelf()
            false
        }

    private fun showOverlay() {
        if (buttonView != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (view, params) = buildOverlay(wm)
        try {
            wm.addView(view, params)
            buttonView = view
            windowManager = wm
        } catch (ignore: Exception) {
            stopSelf()
        }
    }

    /** 悬浮按钮：全局可拖动，长按启停经 MockLocationManager 统一校验 */
    private fun buildOverlay(
        wm: WindowManager,
    ): Pair<FloatingButtonView, WindowManager.LayoutParams> {
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = INITIAL_X
                    y = INITIAL_Y
                }
        val view =
            FloatingButtonView(this).apply {
                this.params = params
                this.windowManager = wm
                onToggle = { reportToggleError() }
            }
        return view to params
    }

    private fun reportToggleError() {
        val error = MockLocationManager.tryToggleFromOverlay()
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        buttonView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (ignore: IllegalArgumentException) {
            }
        }
        buttonView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIFICATION_ID = 2
        private const val INITIAL_X = 40
        private const val INITIAL_Y = 300
    }
}
