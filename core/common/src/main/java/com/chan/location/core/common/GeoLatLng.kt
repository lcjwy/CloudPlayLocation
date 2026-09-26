package com.chan.location.core.common

import kotlin.math.abs

/** 经纬度值对象；未标注坐标系的均为 WGS84 */
data class GeoLatLng(
    val lat: Double,
    val lng: Double,
) {
    fun nearlyEquals(
        other: GeoLatLng,
        epsilon: Double = 1e-6,
    ): Boolean = abs(lat - other.lat) < epsilon && abs(lng - other.lng) < epsilon
}

/** 地图源；放 common 供 data 层持久化使用，避免 core→map 反向依赖 */
enum class MapSource { BAIDU, OSM }
