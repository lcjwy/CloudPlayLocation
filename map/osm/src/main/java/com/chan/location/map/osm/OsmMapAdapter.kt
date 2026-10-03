package com.chan.location.map.osm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.chan.location.map.api.MapAdapter
import com.chan.location.map.api.MapConfig
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** osmdroid 实现：WGS84 直通；中心点回调经 120ms 防抖 */
class OsmMapAdapter(
    context: Context,
    config: MapConfig,
) : MapAdapter {
    private val appContext = context.applicationContext
    private val mapView = MapView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var reportPending = false

    override var onCenterChanged: ((lat: Double, lng: Double) -> Unit)? = null

    override var onZoomChanged: ((zoom: Float) -> Unit)? = null

    override val view: View get() = mapView

    override val currentZoom: Float get() = mapView.zoomLevelDouble.toFloat()

    init {
        ensureConfig(appContext)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.isTilesScaledToDpi = true
        mapView.controller.setZoom(config.zoom.toDouble())
        mapView.setExpectedCenter(GeoPoint(config.lat, config.lng))
        mapView.addMapListener(
            object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    scheduleReport()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    scheduleReport()
                    return false
                }
            },
        )
        reportCenter()
        if (config.myLocationEnabled) setMyLocationEnabled(true)
    }

    companion object {
        private const val REPORT_DELAY_MS = 120L

        @Volatile
        private var configDone = false

        /** 缓存写入应用私有目录，避免存储权限；UA 标识应用 */
        private fun ensureConfig(appContext: Context) {
            if (configDone) return
            synchronized(this) {
                if (configDone) return
                Configuration.getInstance().apply {
                    userAgentValue = appContext.packageName
                    osmdroidBasePath = File(appContext.filesDir, "osmdroid")
                    osmdroidTileCache = File(appContext.cacheDir, "osmdroid-tiles")
                }
                configDone = true
            }
        }
    }

    private fun scheduleReport() {
        if (reportPending) return
        reportPending = true
        handler.postDelayed({
            reportPending = false
            reportCenter()
        }, REPORT_DELAY_MS)
    }

    private fun reportCenter() {
        val center = mapView.mapCenter as? GeoPoint ?: return
        onCenterChanged?.invoke(center.latitude, center.longitude)
        onZoomChanged?.invoke(mapView.zoomLevelDouble.toFloat())
    }

    override fun moveCamera(
        lat: Double,
        lng: Double,
        zoom: Float,
    ) {
        // 先设缩放再动画：顺序相反会让 setZoom 中断进行中的平移动画
        mapView.controller.setZoom(zoom.toDouble())
        mapView.controller.animateTo(GeoPoint(lat, lng))
        onCenterChanged?.invoke(lat, lng)
        onZoomChanged?.invoke(zoom)
    }

    override fun zoomIn() {
        mapView.controller.zoomIn()
    }

    override fun zoomOut() {
        mapView.controller.zoomOut()
    }

    override fun setMyLocationEnabled(enabled: Boolean) {
        if (!enabled) {
            myLocationOverlay?.disableMyLocation()
            return
        }
        val overlay =
            myLocationOverlay ?: run {
                val created = MyLocationNewOverlay(GpsMyLocationProvider(appContext), mapView)
                myLocationOverlay = created
                mapView.overlays.add(created)
                created
            }
        try {
            overlay.enableMyLocation()
        } catch (ignore: SecurityException) {
            // 未授予定位权限时静默关闭蓝点
        }
    }

    override fun moveToMyLocation(): Boolean {
        val fix = myLocationOverlay?.lastFix ?: return false
        mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
        return true
    }

    /** 无对应在线反查服务，交由上层回退系统 Geocoder */
    override suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
    ): String? = null

    override fun onResume() {
        mapView.onResume()
        myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        myLocationOverlay?.disableMyLocation()
        mapView.onPause()
    }

    override fun onDestroy() {
        myLocationOverlay?.disableMyLocation()
        mapView.onDetach()
    }
}
