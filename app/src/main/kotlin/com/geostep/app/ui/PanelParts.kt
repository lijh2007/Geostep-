package com.geostep.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geostep.core.model.*

@Composable
internal fun ToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Medium) }, supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
internal fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }

@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        Text(subtitle, Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingSlider(
    title: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float? = null,
    unit: String = "",
    presets: List<Pair<String, Float>> = emptyList(),
    onValue: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    var inputOpen by remember { mutableStateOf(false) }
    val showReset = defaultValue != null && kotlin.math.abs(local - defaultValue) > 0.05f
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (showReset && defaultValue != null) {
                TextButton(
                    onClick = { local = defaultValue; onValue(defaultValue) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(Icons.Default.Refresh, "恢复默认", Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("默认", style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.tapFeedback(scaleDown = 0.93f) { inputOpen = true },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Default.Edit, "输入精确值", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Slider(value = local, onValueChange = { local = it; onValue(it) }, valueRange = range)
        if (presets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { (label, target) ->
                    val selected = kotlin.math.abs(local - target) < 0.1f
                    SuggestionChip(
                        onClick = { local = target.coerceIn(range); onValue(local) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = if (selected) SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ) else SuggestionChipDefaults.suggestionChipColors(),
                    )
                }
            }
        }
    }
    if (inputOpen) {
        var text by remember { mutableStateOf(if (local % 1f == 0f) "%.0f".format(local) else "%.1f".format(local)) }
        val parsed = text.trim().toFloatOrNull()
        val valid = parsed != null && parsed.isFinite() && parsed in range
        AlertDialog(
            onDismissRequest = { inputOpen = false },
            title = { Text("输入$title") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (unit.isNotEmpty()) "$title ($unit)" else title) },
                    supportingText = { Text("有效范围：${range.start} ～ ${range.endInclusive} $unit") },
                    isError = text.isNotBlank() && !valid,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val v = parsed!!.coerceIn(range)
                        local = v
                        onValue(v)
                        inputOpen = false
                    },
                    enabled = valid,
                ) { Text("确定") }
            },
            dismissButton = {
                if (defaultValue != null) {
                    TextButton(onClick = {
                        local = defaultValue
                        onValue(defaultValue)
                        inputOpen = false
                    }) { Text("恢复默认") }
                }
                TextButton(onClick = { inputOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().tapFeedback(scaleDown = 0.985f) { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}


/**
 * 可折叠分组：参数面板有十几项，平铺一屏放不完。折叠后默认只展开两组，
 * 既能看到主要参数，也不会把次要项（Root 注入、GPS 漂移）藏到找不到。
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    hint: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .tapFeedback(scaleDown = 0.99f) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
            }
        }
    }
}
