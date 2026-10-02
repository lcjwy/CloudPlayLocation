package com.chan.location.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 隐私政策全文弹窗：首启与设置页复查共用；onDismiss 传 null 表示不可点外部关闭 */
private const val PRIVACY_INTRO = "欢迎使用云游。在使用前，请阅读并确认以下内容："

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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    PRIVACY_INTRO,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current,
                )
                PrivacySections.forEach { section ->
                    PrivacySectionText(section)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("不同意") } },
    )
}

/** 单段渲染：标题加粗；重点段正文加粗加大加黑（onSurface） */
@Composable
private fun PrivacySectionText(section: PrivacySection) {
    Text(
        section.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        section.body,
        style =
            if (section.highlighted) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
        fontWeight = if (section.highlighted) FontWeight.Bold else FontWeight.Normal,
        color =
            if (section.highlighted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
