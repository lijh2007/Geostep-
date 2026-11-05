package com.geostep.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geostep.core.model.CheckResult

/**
 * 注入回读校验条：运行中显示，点击任意位置或「校验」按钮都会立即回读一次。
 */
@Composable
internal fun VerificationBanner(checks: List<CheckResult>, verifying: Boolean, onVerify: () -> Unit) {
    val failed = checks.filter { !it.ok }
    Surface(
        modifier = Modifier.liquidGlassChip(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = when {
            failed.isNotEmpty() -> MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f)
            checks.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)
            else -> Color.Transparent
        },
    ) {
        Row(
            Modifier.clickable { onVerify() }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                when {
                    failed.isNotEmpty() -> Icons.Default.Warning
                    checks.isNotEmpty() -> Icons.Default.Verified
                    else -> Icons.Default.Sync
                },
                null,
                Modifier.size(16.dp),
                tint = if (failed.isNotEmpty()) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                when {
                    verifying -> "正在回读校验注入…"
                    failed.isNotEmpty() -> "注入未生效：" + (failed.first().message ?: failed.first().name)
                    checks.isNotEmpty() -> checks.firstOrNull { it.name.contains("步频") }?.message ?: "位置与步频注入已确认"
                    else -> "等待回读校验…"
                },
                Modifier.weight(1f).padding(start = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
            TextButton(onClick = onVerify, enabled = !verifying) { Text("校验") }
        }
    }
}

/**
 * 地图异常提示条：首帧超时或加载过慢时出现，可直接重试或切换地图源。
 */
@Composable
internal fun MapIssueBanner(onRetry: () -> Unit, onChooseMap: () -> Unit) {
    Surface(
        modifier = Modifier.liquidGlassChip(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiOff, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("地图加载较慢，可重试或切换", Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onRetry) { Text("重试") }
            IconButton(onClick = onChooseMap, Modifier.size(32.dp)) { Icon(Icons.Default.Layers, "切换地图", Modifier.size(18.dp)) }
        }
    }
}
