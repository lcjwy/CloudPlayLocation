package com.chan.location.map.baidu

import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/**
 * 百度 SDK 懒初始化：仅隐私同意后才可调用，且必须先于任何 MapView 创建。
 * 幂等，可重复调用。
 */
object BaiduSdkInitializer {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInit(
        appContext: Context,
        privacyAgreed: Boolean,
    ): Boolean {
        if (!initialized) {
            initialized =
                privacyAgreed &&
                runCatching {
                    SDKInitializer.setAgreePrivacy(appContext, true)
                    LocationClient.setAgreePrivacy(true)
                    SDKInitializer.initialize(appContext)
                    SDKInitializer.setCoordType(CoordType.BD09LL)
                }.isSuccess
        }
        return initialized
    }
}
