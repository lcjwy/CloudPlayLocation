package com.chan.location.core.data

import android.content.Context
import com.chan.location.core.common.CoordUtils
import com.chan.location.core.common.GeoLatLng
import com.chan.location.core.data.local.AppDatabase
import com.chan.location.core.data.local.PointDao
import com.chan.location.core.data.local.PointEntity
import com.chan.location.core.data.model.SavedPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

class PointRepository(
    private val dao: PointDao,
) {
    fun history(): Flow<List<SavedPoint>> = dao.history().map { it.map(::toDomain) }

    fun favorites(): Flow<List<SavedPoint>> = dao.favorites().map { it.map(::toDomain) }

    /** 按 id 查点（主页条目定位跳转用）；已被删除时返回 null */
    suspend fun byId(id: Long): SavedPoint? = dao.byId(id)?.let(::toDomain)

    /** 保存到历史：同位置（约 11m 内）只刷新原条目 */
    suspend fun saveHistory(
        name: String,
        wgs: GeoLatLng,
    ): SavedPoint = upsert(name, wgs, favorite = null)

    /** 收藏：同位置仅改收藏标记，不新建条目 */
    suspend fun saveFavorite(
        name: String,
        wgs: GeoLatLng,
    ): SavedPoint = upsert(name, wgs, favorite = true)

    private suspend fun upsert(
        name: String,
        wgs: GeoLatLng,
        favorite: Boolean?,
    ): SavedPoint {
        val now = System.currentTimeMillis()
        val eps = 0.00005
        val bd = CoordUtils.wgs2bd09(wgs)
        val finalName = name.ifBlank { String.format(Locale.US, "%.6f, %.6f", wgs.lat, wgs.lng) }
        val existing = dao.findNear(wgs.lat - eps, wgs.lat + eps, wgs.lng - eps, wgs.lng + eps)
        return if (existing != null) {
            val updated =
                existing.copy(
                    name = finalName,
                    bdLat = bd.lat,
                    bdLng = bd.lng,
                    isFavorite = favorite ?: existing.isFavorite,
                    lastUsedAt = now,
                )
            dao.update(updated)
            toDomain(updated)
        } else {
            val entity =
                PointEntity(
                    name = finalName,
                    wgsLat = wgs.lat,
                    wgsLng = wgs.lng,
                    bdLat = bd.lat,
                    bdLng = bd.lng,
                    isFavorite = favorite ?: false,
                    createdAt = now,
                    lastUsedAt = now,
                )
            toDomain(entity.copy(id = dao.insert(entity)))
        }
    }

    suspend fun setFavorite(
        id: Long,
        favorite: Boolean,
    ) = dao.setFavorite(id, favorite)

    suspend fun delete(id: Long) = dao.delete(id)

    /** 批量删除（列表多选管理）；空列表忽略，避免拼出 IN () 非法 SQL */
    suspend fun deleteAll(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteAll(ids)
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    private fun toDomain(e: PointEntity) =
        SavedPoint(e.id, e.name, e.wgsLat, e.wgsLng, e.isFavorite, e.lastUsedAt)
}

/** 组装入口：上层只依赖 PointRepository，不感知 Room 类型 */
fun buildPointRepository(context: Context): PointRepository =
    PointRepository(AppDatabase.build(context).pointDao())
