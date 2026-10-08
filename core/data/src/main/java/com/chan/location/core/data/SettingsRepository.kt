package com.chan.location.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chan.location.core.common.MapSource
import com.chan.location.core.data.model.SelectedPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 应用设置（DataStore）。privacyAgreed 为 null 表示用户尚未选择 */
class SettingsRepository(
    private val context: Context,
) {
    private object K {
        val mapSource = stringPreferencesKey("map_source")
        val intervalMs = intPreferencesKey("interval_ms")
        val floating = booleanPreferencesKey("floating_enabled")
        val privacy = booleanPreferencesKey("privacy_agreed")
        val mockEnabled = booleanPreferencesKey("mock_enabled")
        val selName = stringPreferencesKey("sel_name")
        val selLat = stringPreferencesKey("sel_lat")
        val selLng = stringPreferencesKey("sel_lng")
        val baiduKey = stringPreferencesKey("baidu_key")
    }

    val mapSource: Flow<MapSource> =
        context.dataStore.data.map { p ->
            p[K.mapSource]?.let { name -> MapSource.entries.firstOrNull { it.name == name } }
                ?: MapSource.BAIDU
        }

    val intervalMs: Flow<Int> =
        context.dataStore.data.map {
            it[K.intervalMs]?.coerceIn(10, 100)
                ?: 100
        }

    val floatingEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.floating] ?: false }

    val privacyAgreed: Flow<Boolean?> = context.dataStore.data.map { it[K.privacy] }

    val mockEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.mockEnabled] ?: false }

    val selectedPoint: Flow<SelectedPoint?> =
        context.dataStore.data.map { p ->
            val lat = p[K.selLat]?.toDoubleOrNull() ?: return@map null
            val lng = p[K.selLng]?.toDoubleOrNull() ?: return@map null
            SelectedPoint(p[K.selName].orEmpty(), lat, lng)
        }

    /** 用户自定义百度地图 Key；空串 = 未设置（使用应用内置 Key） */
    val baiduKey: Flow<String> = context.dataStore.data.map { it[K.baiduKey].orEmpty() }

    suspend fun setMapSource(source: MapSource) {
        context.dataStore.edit { it[K.mapSource] = source.name }
    }

    suspend fun setIntervalMs(ms: Int) {
        context.dataStore.edit { it[K.intervalMs] = ms.coerceIn(10, 100) }
    }

    suspend fun setFloatingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[K.floating] = enabled }
    }

    suspend fun setPrivacyAgreed(agreed: Boolean?) {
        context.dataStore.edit {
            if (agreed == null) it.remove(K.privacy) else it[K.privacy] = agreed
        }
    }

    suspend fun setMockEnabled(running: Boolean) {
        context.dataStore.edit { it[K.mockEnabled] = running }
    }

    /** 保存自定义百度 Key；空白视为清除（恢复内置 Key） */
    suspend fun setBaiduKey(key: String) {
        context.dataStore.edit {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) it.remove(K.baiduKey) else it[K.baiduKey] = trimmed
        }
    }

    suspend fun setSelectedPoint(point: SelectedPoint?) {
        context.dataStore.edit {
            if (point == null) {
                it.remove(K.selName)
                it.remove(K.selLat)
                it.remove(K.selLng)
            } else {
                it[K.selName] = point.name
                it[K.selLat] = point.wgsLat.toString()
                it[K.selLng] = point.wgsLng.toString()
            }
        }
    }
}
