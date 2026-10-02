package com.chan.location.map.baidu

import android.content.Context
import android.view.View
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.model.LatLng
import com.chan.location.core.common.CoordUtils
import com.chan.location.core.common.GeoLatLng
import com.chan.location.map.api.MapAdapter
import com.chan.location.map.api.MapConfig

/** 百度实现：显示层 BD09LL，出入参转 WGS84；程序化移动时抑制中心点回调 */
class BaiduMapAdapter(
    context: Context,
    config: MapConfig,
) : MapAdapter {
    private val appContext = context.applicationContext
    private val mapView = MapView(context)
    private val baiduMap: BaiduMap = mapView.map
    private var locationClient: LocationClient? = null
    private var lastFix: BDLocation? = null
    private var myLocationEnabled = false
    private var autoMovedToFix = false

    /** 程序化相机移动期间不向 UI 回报（避免坐标抖动），结束时复位 */
    private var programmaticMove = false

    override var onCenterChanged: ((lat: Double, lng: Double) -> Unit)? = null

    override val view: View get() = mapView

    override val currentZoom: Float get() = baiduMap.mapStatus.zoom

    private val listener =
        object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(status: MapStatus?) {}

            override fun onMapStatusChangeStart(
                status: MapStatus?,
                reason: Int,
            ) {
                // 程序化动画若无回调（已在目标点/缩放极限时），标志位等不到 Finish 复位；
                // 手势起始强制解除抑制，保证用户拖动始终回报中心点
                if (reason == BaiduMap.OnMapStatusChangeListener.REASON_GESTURE) {
                    programmaticMove = false
                }
            }

            override fun onMapStatusChange(status: MapStatus?) {
                if (!programmaticMove) status.report()
            }

            override fun onMapStatusChangeFinish(status: MapStatus?) {
                if (programmaticMove) {
                    programmaticMove = false
                } else {
                    status.report()
                }
            }

            private fun MapStatus?.report() {
                val s = this ?: return
                val wgs = CoordUtils.bd092wgs(GeoLatLng(s.target.latitude, s.target.longitude))
                onCenterChanged?.invoke(wgs.lat, wgs.lng)
            }
        }

    init {
        baiduMap.mapType = BaiduMap.MAP_TYPE_NORMAL
        baiduMap.setOnMapStatusChangeListener(listener)
        moveCamera(config.lat, config.lng, config.zoom)
        if (config.myLocationEnabled) setMyLocationEnabled(true)
    }

    override fun moveCamera(
        lat: Double,
        lng: Double,
        zoom: Float,
    ) {
        val bd = CoordUtils.wgs2bd09(GeoLatLng(lat, lng))
        programmaticMove = true
        baiduMap.animateMapStatus(
            MapStatusUpdateFactory.newMapStatus(
                MapStatus
                    .Builder()
                    .target(LatLng(bd.lat, bd.lng))
                    .zoom(zoom)
                    .build(),
            ),
        )
        onCenterChanged?.invoke(lat, lng)
    }

    override fun zoomIn() {
        programmaticMove = true
        baiduMap.animateMapStatus(MapStatusUpdateFactory.zoomIn())
    }

    override fun zoomOut() {
        programmaticMove = true
        baiduMap.animateMapStatus(MapStatusUpdateFactory.zoomOut())
    }

    override fun setMyLocationEnabled(enabled: Boolean) {
        myLocationEnabled = enabled
        baiduMap.isMyLocationEnabled = enabled
        if (enabled) {
            baiduMap.setMyLocationConfiguration(
                MyLocationConfiguration(MyLocationConfiguration.LocationMode.NORMAL, true, null),
            )
            startLocationClient()
        } else {
            locationClient?.stop()
        }
    }

    private fun startLocationClient() {
        locationClient?.let {
            it.start()
            return
        }
        val client = LocationClient(appContext)
        val option =
            LocationClientOption().apply {
                locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                setCoorType("bd09ll")
                // 蓝点参考用途，5s 一次足够；1s 连续定位耗电明显
                setScanSpan(5000)
                setIsNeedAddress(false)
                setIsNeedLocationDescribe(false)
            }
        client.locOption = option
        client.registerLocationListener(
            object : BDAbstractLocationListener() {
                override fun onReceiveLocation(location: BDLocation) {
                    if (location.latitude == 0.0 && location.longitude == 0.0) return
                    lastFix = location
                    if (!myLocationEnabled) return
                    baiduMap.setMyLocationData(
                        MyLocationData
                            .Builder()
                            .accuracy(location.radius)
                            .latitude(location.latitude)
                            .longitude(location.longitude)
                            .build(),
                    )
                    if (!autoMovedToFix) {
                        autoMovedToFix = true
                        moveCamera(location.latitude, location.longitude, currentZoom)
                    }
                }
            },
        )
        client.start()
        locationClient = client
    }

    override fun moveToMyLocation(): Boolean {
        val fix = lastFix ?: return false
        baiduMap.animateMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(
                LatLng(fix.latitude, fix.longitude),
                currentZoom,
            ),
        )
        return true
    }

    override suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
    ): String? = baiduReverseGeocode(GeoLatLng(lat, lng))

    override fun onResume() {
        mapView.onResume()
        // 页面回前台才恢复定位，后台不持续扫描（耗电优化）
        if (myLocationEnabled) locationClient?.start()
    }

    override fun onPause() {
        locationClient?.stop()
        mapView.onPause()
    }

    override fun onDestroy() {
        locationClient?.stop()
        mapView.onDestroy()
    }
}
