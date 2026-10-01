package com.chan.location.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chan.location.core.data.model.SavedPoint

/** 列表多选状态：selecting 是否处于多选模式；进程重建不恢复 */
internal class HomeSelectionState {
    var selecting by mutableStateOf(false)
    var pendingDelete by mutableStateOf(false)
    val ids = mutableStateListOf<Long>()

    fun enter() {
        selecting = true
    }

    fun exit() {
        selecting = false
        pendingDelete = false
        ids.clear()
    }

    fun toggle(id: Long) {
        if (!ids.remove(id)) ids.add(id)
    }

    fun selectAll(
        list: List<SavedPoint>,
        all: Boolean,
    ) {
        ids.clear()
        if (all) ids.addAll(list.map { it.id })
    }
}
