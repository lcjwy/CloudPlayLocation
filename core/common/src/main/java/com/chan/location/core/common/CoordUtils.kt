package com.chan.location.core.common

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 / GCJ02 / BD09 坐标转换（纯数学，移植自 Gogogo MapUtils）。
 * 本应用内部存储与注入一律 WGS84，仅百度地图显示层需要 BD09，缺此转换会偏移数百米。
 */
object CoordUtils {
    private const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgs2bd09(p: GeoLatLng): GeoLatLng = gcj2bd09(wgs2gcj(p))

    fun bd092wgs(p: GeoLatLng): GeoLatLng = gcj2wgs(bd092gcj(p))

    fun wgs2gcj(p: GeoLatLng): GeoLatLng {
        if (outOfChina(p)) return p
        val (dLat, dLng) = delta(p.lat, p.lng)
        return GeoLatLng(p.lat + dLat, p.lng + dLng)
    }

    fun gcj2wgs(p: GeoLatLng): GeoLatLng {
        if (outOfChina(p)) return p
        val (dLat, dLng) = delta(p.lat, p.lng)
        return GeoLatLng(p.lat * 2 - (p.lat + dLat), p.lng * 2 - (p.lng + dLng))
    }

    fun gcj2bd09(p: GeoLatLng): GeoLatLng {
        val z = sqrt(p.lng * p.lng + p.lat * p.lat) + 0.00002 * sin(p.lat * X_PI)
        val theta = atan2(p.lat, p.lng) + 0.000003 * cos(p.lng * X_PI)
        return GeoLatLng(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    fun bd092gcj(p: GeoLatLng): GeoLatLng {
        val x = p.lng - 0.0065
        val y = p.lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return GeoLatLng(z * sin(theta), z * cos(theta))
    }

    private fun outOfChina(p: GeoLatLng): Boolean =
        p.lng < 72.004 || p.lng > 137.8347 || p.lat < 0.8293 || p.lat > 55.8271

    private fun delta(
        lat: Double,
        lng: Double,
    ): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun transformLat(
        x: Double,
        y: Double,
    ): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(
        x: Double,
        y: Double,
    ): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
