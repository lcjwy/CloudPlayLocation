package com.chan.location.core.common

import android.content.Context
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build

/** 检测本应用是否已被系统选为"模拟位置信息应用"（试探法，移植自 Gogogo GoUtils） */
object MockLocationAccess {
    /**
     * addTestProvider 成功即视为已授权（与 Gogogo 一致：先定论、后清理）；
     * 清理探测痕迹的调用单独兜底，其失败不影响判定——部分 ROM 在系统位置
     * 关闭时会对 setTestProviderEnabled/removeTestProvider 抛异常，若混入
     * 判定会导致"已选择模拟应用却被误判未选择"，开关无法启动。
     */
    @Suppress("TooGenericExceptionCaught")
    fun isGranted(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var granted = false
        try {
            addProbeProvider(lm)
            granted = true
        } catch (ignore: SecurityException) {
            // 未被选为模拟位置信息应用
        } catch (ignore: Exception) {
            // 个别厂商 ROM 的其它异常：按未授权处理
        }
        if (granted) {
            try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (ignore: Exception) {
                // 清理失败不影响判定
            }
        }
        return granted
    }

    private fun addProbeProvider(lm: LocationManager) {
        // 废弃的 10 参重载 + 常量（Gogogo 同款，含 API 31+）：Builder 新重载在部分 ROM 行为不一致
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            true,
            false,
            false,
            true,
            true,
            true,
            powerHigh(),
            accuracyFine(),
        )
    }

    @Suppress("DEPRECATION")
    private fun powerHigh(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties.POWER_USAGE_HIGH
        } else {
            // minSdk 26：26–30 只有 Criteria 常量
            android.location.Criteria.POWER_HIGH
        }

    @Suppress("DEPRECATION")
    private fun accuracyFine(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties.ACCURACY_FINE
        } else {
            android.location.Criteria.ACCURACY_FINE
        }
}
