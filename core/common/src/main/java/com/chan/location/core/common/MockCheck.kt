package com.chan.location.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings

/** 启用虚拟位置前的检查项 */
enum class MockCheckError {
    LOCATION_PERMISSION,
    MOCK_NOT_SELECTED,
    GPS_DISABLED,
    OVERLAY_PERMISSION,
}

object MockCheck {
    /** 按序校验，返回第一个不通过的项；null 表示全部通过 */
    fun validate(
        context: Context,
        overlayRequired: Boolean,
    ): MockCheckError? =
        when {
            !hasLocationPermission(context) -> MockCheckError.LOCATION_PERMISSION
            !MockLocationAccess.isGranted(context) -> MockCheckError.MOCK_NOT_SELECTED
            !isGpsEnabled(context) -> MockCheckError.GPS_DISABLED
            overlayRequired &&
                !Settings.canDrawOverlays(
                    context,
                ) -> MockCheckError.OVERLAY_PERMISSION
            else -> null
        }

    fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /** WiFi 开启时系统可能基于 WiFi 扫描算出真实位置，导致虚拟位置"闪回"（仅提示，不阻断） */
    fun isWifiEnabled(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled ?: false
    }

    /** 系统「Wi-Fi 扫描」开关：即使 WiFi 已关也允许扫描定位，是闪回的另一来源；个别 ROM 读取受限则按关闭处理 */
    fun isWifiScanAlwaysAvailable(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return runCatching { wm?.isScanAlwaysAvailable ?: false }.getOrDefault(false)
    }

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
