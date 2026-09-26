package com.chan.location.core.data.model

/** 已保存点位（Room 实体的领域视图，坐标 WGS84） */
data class SavedPoint(
    val id: Long,
    val name: String,
    val wgsLat: Double,
    val wgsLng: Double,
    val isFavorite: Boolean,
    val lastUsedAt: Long,
)

/** 当前选中的虚拟位置快照（DataStore 持久化，防列表条目被删后丢失） */
data class SelectedPoint(
    val name: String,
    val wgsLat: Double,
    val wgsLng: Double,
)
