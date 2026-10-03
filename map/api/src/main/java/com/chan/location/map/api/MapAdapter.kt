package com.chan.location.map.api

import android.content.Context
import android.view.View
import com.chan.location.core.common.MapSource

/**
 * 地图抽象：对外一律 WGS84 坐标，各实现在内部完成显示坐标系转换。
 * 生命周期由使用方（Compose DisposableEffect）驱动。
 */
interface MapAdapter {
    val view: View

    /** 中心点变化回调（WGS84）：拖动、缩放、移动相机时触发 */
    var onCenterChanged: ((lat: Double, lng: Double) -> Unit)?

    /** 缩放级别变化回调：拖动/双指缩放/程序化缩放结束时触发，供比例尺显示 */
    var onZoomChanged: ((zoom: Float) -> Unit)?

    val currentZoom: Float

    fun moveCamera(
        lat: Double,
        lng: Double,
        zoom: Float,
    )

    fun zoomIn()

    fun zoomOut()

    /** 开启真实位置蓝点（需已授予定位权限；实现可不支持，空操作） */
    fun setMyLocationEnabled(enabled: Boolean)

    /** 相机移动到真实位置；尚无定位结果返回 false */
    fun moveToMyLocation(): Boolean

    /** 反查地点名（WGS84 入参）；实现不支持或失败返回 null，由调用方回退系统 Geocoder */
    suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
    ): String?

    fun onResume()

    fun onPause()

    fun onDestroy()
}

data class MapConfig(
    val source: MapSource,
    val lat: Double,
    val lng: Double,
    val zoom: Float = 16f,
    val myLocationEnabled: Boolean = false,
    /** 未同意隐私政策时，实现应回退到无需隐私初始化的地图源 */
    val privacyAgreed: Boolean = false,
)

/** 由 :app 提供具体实现的选择与注入 */
fun interface MapAdapterFactory {
    fun create(
        context: Context,
        config: MapConfig,
    ): MapAdapter
}
