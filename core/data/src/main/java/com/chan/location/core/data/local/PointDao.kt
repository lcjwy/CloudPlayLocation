package com.chan.location.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PointDao {
    /** 历史 = 全部点位按最近使用倒序；收藏条目仍保留在历史中（收藏只是标记，不搬家） */
    @Query("SELECT * FROM points ORDER BY lastUsedAt DESC")
    fun history(): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE isFavorite = 1 ORDER BY lastUsedAt DESC")
    fun favorites(): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE id = :id")
    suspend fun byId(id: Long): PointEntity?

    @Query(
        "SELECT * FROM points WHERE wgsLat BETWEEN :minLat AND :maxLat " +
            "AND wgsLng BETWEEN :minLng AND :maxLng LIMIT 1",
    )
    suspend fun findNear(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): PointEntity?

    @Insert
    suspend fun insert(entity: PointEntity): Long

    @Update
    suspend fun update(entity: PointEntity)

    @Query("DELETE FROM points WHERE id = :id")
    suspend fun delete(id: Long)

    /** 空列表会生成非法 SQL（IN ()），调用方需保证非空 */
    @Query("DELETE FROM points WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("UPDATE points SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(
        id: Long,
        favorite: Boolean,
    )

    @Query("UPDATE points SET lastUsedAt = :time WHERE id = :id")
    suspend fun touch(
        id: Long,
        time: Long,
    )
}
