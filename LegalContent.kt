package com.baining.str.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val LEGAL_VERSION = "2026-09-19"

private val agreementSections = listOf(
    "一、服务说明" to "白柠截图提供本机截图、录屏、悬浮控制和媒体文件管理。源码模式调用 Android 系统捕获工具；模块模式在取得 Root 授权后，只读访问显示设备节点（例如 /dev/graphics/fb0、/dev/dri/card0）以生成图像，不写入系统分区。",
    "二、权限与 Root" to "应用仅在功能需要时申请通知、存储、悬浮窗、录音和 Root 权限。模块模式依赖设备与 Root 管理器支持。请在理解权限用途后自行决定是否授予；撤销权限会使对应功能停止工作。",
    "三、数据与隐私" to "截图、录屏、设置和诊断日志默认仅保存在你的设备中，本应用不包含账号系统、广告 SDK、统计 SDK或云端上传功能。你选择的保存目录可能被其他具有存储权限的应用读取，请自行保护敏感媒体。",
    "四、使用规则" to "你应仅捕获自己有权访问、保存和使用的内容，并遵守设备、应用及内容提供方的约定。请勿将本应用用于侵犯隐私、著作权、商业秘密或绕过依法设置的访问控制。",
    "五、录制与存储" to "长录屏通过约 170 秒分段连续录制并在停止时合并，最长 10 小时。可用时长仍受电量、温度、系统编码器、剩余空间和厂商 ROM 限制。合并失败时应用会保留有效分段，避免已录内容丢失。",
    "六、风险与责任" to "Root、长时间编码和读取显示设备节点可能增加耗电、发热或兼容性风险。请在重要操作前备份数据，并确保设备有足够空间。因设备环境、第三方内容或不当使用产生的损失，由使用者自行承担。",
    "七、协议更新" to "协议有重要变更时，应用会通过新的协议版本再次征求同意。继续使用前，你可以在“系统”页面随时查看本协议和开源许可。"
)

private val openSourceItems = listOf(
    "Kotlin 2.3.10 — Apache License 2.0 — kotlinlang.org",
    "AndroidX Core KTX 1.10.1 — Apache License 2.0 — developer.android.com/jetpack/androidx",
    "AndroidX Activity Compose 1.8.0 — Apache License 2.0",
    "AndroidX Lifecycle 2.8.7 — Apache License 2.0",
    "Jetpack Compose BOM 2026.01.01 / UI / Material 3 — Apache License 2.0",
    "Material Icons Extended 1.7.6 — Apache License 2.0",
    "AndroidX DataStore Preferences 1.1.1 — Apache License 2.0",
    "Accompanist Permissions 0.36.0 — Apache License 2.0 — github.com/google/accompanist"
)

@Composable
fun FirstRunAgreementScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Filled.Gavel, null, tint = MaterialTheme.colorScheme.primary)
            Text("使用前，请确认用户协议", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("协议版本 $LEGAL_VERSION · 约 2 分钟", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                LegalDocument(Modifier.padding(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { checked = it })
                Text("我已阅读并同意《用户协议与隐私说明》", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onAgree, Modifier.fillMaxWidth(), enabled = checked) { Text("同意并进入白柠截图") }
            OutlinedButton(onDecline, Modifier.fillMaxWidth()) { Text("不同意并退出") }
        }
    }
}

@Composable
fun LegalDocumentsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Gavel, null) },
        title = { Text("用户协议与开源许可") },
        text = { LegalDocument(Modifier.fillMaxWidth().height(520.dp)) },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun LegalDocument(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        agreementSections.forEach { (title, body) ->
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Text("开源项目标识", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("本应用使用以下开源组件。Apache License 2.0 全文可在 apache.org/licenses/LICENSE-2.0 查看。", style = MaterialTheme.typography.bodySmall)
        openSourceItems.forEach { item -> Text("• $item", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
