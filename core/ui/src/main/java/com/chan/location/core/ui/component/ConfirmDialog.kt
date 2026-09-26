package com.chan.location.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 双按钮确认弹窗；dismissText 传 null 隐藏取消按钮 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String? = null,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (text != null) Text(text)
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = {
            if (dismissText != null) TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}
