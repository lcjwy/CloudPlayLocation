package com.chan.location.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/** 启用虚拟位置前的检查项（不校验系统 GPS 开关：推荐先启动虚拟位置、再手动开系统定位） */
enum class MockCheckError {
    LOCATION_PERMISSION,
    MOCK_NOT_SELECTED,
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
            overlayRequired &&
                !Settings.canDrawOverlays(
                    context,
                ) -> MockCheckError.OVERLAY_PERMISSION
            else -> null
        }

    fun hasLocationPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** WiFi 开启时系统可能基于 WiFi 扫描算出真实位置，导致虚拟位置"闪回"（仅提示，不阻断） */
    fun isWifiEnabled(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled ?: false
    }

    /** 系统「Wi-Fi 扫描」开关：即使 WiFi 已关也允许扫描定位，是闪回的另一来源；个别 ROM 读取受限则按关闭处理 */
    @Suppress("DEPRECATION") // isScanAlwaysAvailable 虽标记废弃，仍是读取该开关的唯一途径
    fun isWifiScanAlwaysAvailable(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return runCatching { wm?.isScanAlwaysAvailable ?: false }.getOrDefault(false)
    }

    /** 系统位置总开关。注册重试用它区分"位置关闭（可逆，等待即可）"与
     *  "模拟应用被取消选择（不可自愈，需放弃回滚）" */
    fun isLocationEnabled(context: Context): Boolean {
        val lm =
            context.applicationContext.getSystemService(
                Context.LOCATION_SERVICE,
            ) as? LocationManager
                ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION") // API 26–27 读位置模式的唯一途径
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
