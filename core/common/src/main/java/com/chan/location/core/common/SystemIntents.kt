package com.chan.location.core.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** 系统设置页跳转 */
object SystemIntents {
    /** 开发者选项（设置"模拟位置信息应用"） */
    fun developerOptions(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    /** 位置信息开关页 */
    fun locationSource(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /** WLAN 设置页（关闭 WiFi 避免 WiFi 定位引起位置漂移） */
    fun wifiSettings(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    /** 系统「Wi-Fi 扫描」页（隐藏 action，API 23+，部分机型无此页） */
    fun wifiScanningSettings(): Intent = Intent("android.settings.LOCATION_SCANNING_SETTINGS")

    /** 本应用的悬浮窗授权页 */
    fun overlayPermission(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** 安全启动系统页面；目标不存在可回落到备用页面 */
    fun start(
        context: Context,
        intent: Intent,
        fallback: Intent? = null,
    ) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignore: ActivityNotFoundException) {
            if (fallback != null) {
                start(context, fallback)
            } else {
                Toast.makeText(context, "未找到对应系统页面", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
