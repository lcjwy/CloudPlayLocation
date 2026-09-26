package com.chan.location.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chan.location.core.ui.component.PrivacyPolicyText

/** 首启隐私弹窗：不同意则不初始化百度 SDK（地图强制开源源） */
@Composable
fun PrivacyDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
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
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.padding(start = 8.dp)) {
                Text("不同意")
            }
        },
    )
}
