package com.chan.location.map.baidu

import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/**
 * 百度 SDK 懒初始化：仅隐私同意后才可调用，且必须先于任何 MapView 创建。
 * 幂等，可重复调用；SDK 为进程级单次初始化，改 Key 需重启应用生效。
 */
object BaiduSdkInitializer {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInit(
        appContext: Context,
        privacyAgreed: Boolean,
        customKey: String? = null,
    ): Boolean {
        if (!initialized) {
            initialized =
                privacyAgreed &&
                runCatching {
                    SDKInitializer.setAgreePrivacy(appContext, true)
                    LocationClient.setAgreePrivacy(true)
                    // 官方运行时覆盖 AK，必须先于 initialize；空值不覆盖（用内置 Key）
                    customKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        SDKInitializer.setApiKey(
                            it,
                        )
                    }
                    SDKInitializer.initialize(appContext)
                    SDKInitializer.setCoordType(CoordType.BD09LL)
                }.isSuccess
        }
        return initialized
    }
}
