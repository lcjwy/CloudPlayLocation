package com.chan.location.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 坐标转换正确性兜底：不依赖 golden 值，用往返一致性 + 物理合理性约束。
 * 公式为全国统一近似算法（Gogogo 同源），精度约 1e-6 度（分米级）。
 */
class CoordUtilsTest {
    /** 国内代表性坐标（WGS84）：华北/华东/华南/西北/东北高纬 */
    private val samples =
        listOf(
            GeoLatLng(39.907345, 116.391385), // 北京
            GeoLatLng(31.230371, 121.473713), // 上海
            GeoLatLng(23.129077, 113.264384), // 广州
            GeoLatLng(43.825592, 87.616880), // 乌鲁木齐
            GeoLatLng(53.472195, 122.331280), // 漠河
        )

    @Test
    fun wgs2bd09_roundTrip() {
        samples.forEach { p ->
            val back = CoordUtils.bd092wgs(CoordUtils.wgs2bd09(p))
            assertTrue(
                "$p -> $back",
                abs(back.lat - p.lat) < 1e-4 && abs(back.lng - p.lng) < 1e-4,
            )
        }
    }

    @Test
    fun wgs2gcj_roundTrip() {
        samples.forEach { p ->
            val back = CoordUtils.gcj2wgs(CoordUtils.wgs2gcj(p))
            assertTrue(
                "$p -> $back",
                abs(back.lat - p.lat) < 1e-4 && abs(back.lng - p.lng) < 1e-4,
            )
        }
    }

    @Test
    fun bd092wgs_roundTrip() {
        samples.forEach { p ->
            val back = CoordUtils.wgs2bd09(CoordUtils.bd092wgs(p))
            assertTrue(
                "$p -> $back",
                abs(back.lat - p.lat) < 1e-4 && abs(back.lng - p.lng) < 1e-4,
            )
        }
    }

    @Test
    fun outOfChina_gcj_passthrough() {
        // 国测局偏移只覆盖中国大陆范围，境外 GCJ02 层必须原样返回
        // （否则注入境外坐标会被错误偏移）
        val overseas =
            listOf(
                GeoLatLng(40.7128, -74.0060), // 纽约
                GeoLatLng(51.5074, -0.1278), // 伦敦
                GeoLatLng(-33.8688, 151.2093), // 悉尼
                GeoLatLng(0.0, 0.0),
            )
        overseas.forEach { p ->
            assertEquals(p, CoordUtils.wgs2gcj(p))
            assertEquals(p, CoordUtils.gcj2wgs(p))
        }
    }

    @Test
    fun outOfChina_bd09_stillApplies() {
        // 百度瓦片全球使用 BD09 坐标：境外无国测局加密但 BD09 固定偏移仍在，
        // 因此 BD09 转换不做境外直通（有 ~0.006 度 ≈ 600m 的固定偏移）
        val overseas =
            listOf(
                GeoLatLng(40.7128, -74.0060), // 纽约
                GeoLatLng(51.5074, -0.1278), // 伦敦
                GeoLatLng(-33.8688, 151.2093), // 悉尼
                GeoLatLng(0.0, 0.0),
            )
        overseas.forEach { p ->
            val bd = CoordUtils.wgs2bd09(p)
            assertTrue(
                "$p -> $bd",
                abs(bd.lat - p.lat) in 0.004..0.01 && abs(bd.lng - p.lng) in 0.004..0.01,
            )
        }
    }

    @Test
    fun gcj_offset_isMeterLevel() {
        // 国测局偏移为百米级（约 0.001~0.007 度）：过小说明未偏移，过大说明公式错误
        samples.forEach { p ->
            val gcj = CoordUtils.wgs2gcj(p)
            assertTrue(
                "$p -> $gcj",
                abs(gcj.lat - p.lat) in 0.0005..0.01 && abs(gcj.lng - p.lng) in 0.0005..0.01,
            )
        }
    }
}
