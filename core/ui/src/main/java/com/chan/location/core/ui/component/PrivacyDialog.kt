package com.chan.location.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 隐私政策全文弹窗：首启与设置页复查共用；onDismiss 传 null 表示不可点外部关闭 */
@Composable
fun PrivacyPolicyDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text("隐私政策") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(PrivacyPolicyText, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("不同意") } },
    )
}
