package com.chan.location.map.baidu

import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.chan.location.core.common.CoordUtils
import com.chan.location.core.common.GeoLatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/** 反查超时：AK 无效/断网时失败回调可能不触发，必须超时兜底 */
private const val GEOCODE_TIMEOUT_MS = 3_000L

/**
 * 百度反查地点名（WGS84 入参，内部转 BD09LL）：需 SDK 已随地图初始化且 AK 有效。
 * 失败或超时返回 null，由上层回退系统 Geocoder。
 */
@Suppress("TooGenericExceptionCaught") // SDK 未初始化等非预期异常统一按反查失败处理
internal suspend fun baiduReverseGeocode(wgs: GeoLatLng): String? {
    val bd = CoordUtils.wgs2bd09(wgs)
    return try {
        withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val coder = GeoCoder.newInstance()
                // 回调与超时取消只允许一方执行 destroy，避免二次释放
                val done = AtomicBoolean(false)

                fun finish(body: () -> Unit) {
                    if (done.compareAndSet(false, true)) body()
                }
                coder.setOnGetGeoCodeResultListener(
                    object : OnGetGeoCoderResultListener {
                        override fun onGetGeoCodeResult(result: GeoCodeResult?) {}

                        override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                            finish {
                                coder.destroy()
                                if (cont.isActive) {
                                    cont.resume(result?.address?.takeIf { it.isNotBlank() })
                                }
                            }
                        }
                    },
                )
                cont.invokeOnCancellation { finish { coder.destroy() } }
                coder.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(bd.lat, bd.lng)))
            }
        }
    } catch (e: CancellationException) {
        // 结构化取消必须透传，不能当失败吞掉
        throw e
    } catch (ignore: Exception) {
        // SDK 未初始化/进程服务异常等：视为反查失败
        null
    }
}
