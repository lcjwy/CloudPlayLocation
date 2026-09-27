package com.chan.location.core.common

import android.content.Context
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build

/** 检测本应用是否已被系统选为"模拟位置信息应用"（试探法，移植自 Gogogo GoUtils） */
object MockLocationAccess {
    /**
     * addTestProvider 成功即已授权；SecurityException 表示未授权，
     * IllegalArgumentException 表示已注册过（同样视为已授权）。
     * 个别厂商 ROM 会抛出其它异常，兜底按未授权处理。
     */
    @Suppress("TooGenericExceptionCaught")
    fun isGranted(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            addProbeProvider(lm)
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        } catch (ignore: SecurityException) {
            false
        } catch (ignore: IllegalArgumentException) {
            true
        } catch (ignore: Exception) {
            false
        }
    }

    private fun addProbeProvider(lm: LocationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                ProviderProperties
                    .Builder()
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                    .build(),
            )
        } else {
            // minSdk 26：26–30 只有 Criteria 重载可用
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
                android.location.Criteria.POWER_HIGH,
                android.location.Criteria.ACCURACY_FINE,
            )
        }
    }
}
