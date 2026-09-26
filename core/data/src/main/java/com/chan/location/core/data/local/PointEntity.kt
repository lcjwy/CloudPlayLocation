package com.chan.location.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 选中过的点位；wgs 为主坐标，bd 为冗余（供百度侧直接使用） */
@Entity(tableName = "points")
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val wgsLat: Double,
    val wgsLng: Double,
    val bdLat: Double,
    val bdLng: Double,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val lastUsedAt: Long,
)
