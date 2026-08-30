package com.atelierapps.vault.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import com.atelierapps.vault.data.entity.RuleMatchKind
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton

/** Friendly labels for the capture-type picker (UNKNOWN is never a rule target). */
private val TYPE_CHOICES = listOf(
    SourceType.CAMERA to "Camera",
    SourceType.LOCAL_IMPORT to "Gallery import",
    SourceType.FOLDER_IMPORT to "Folder import",
    SourceType.SHARE to "Shared in",
)

private fun typeLabel(name: String): String =
    TYPE_CHOICES.firstOrNull { it.first.name == name }?.second ?: name

@Composable
fun RulesScreen(vm: RulesViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val rules by vm.rules.collectAsState()
    val tags by vm.tags.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VaultIconButton(Icons.Outlined.Close, "Close", onClose)
                Text(
                    "Auto-tag rules",
                    color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            Text(
                "Tags are applied automatically as items are saved, based on where " +
                    "they came from. New rules apply to items saved from now on.",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (rules.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No rules yet.\nTap + to add one.",
                        color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(
                            rule = rule,
                            onToggle = { vm.setEnabled(rule.id, it) },
                            onDelete = { vm.delete(rule.id) },
                        )
                        HorizontalDivider(color = Color(0xFF20272C))
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            containerColor = Brass,
            contentColor = Color(0xFF1A1509),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Text("+", fontSize = 26.sp) }
    }

    if (showAdd) {
        AddRuleDialog(
            existingTags = tags,
            onAdd = { kind, value, tagNames -> vm.addRule(kind, value, tagNames); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun RuleRow(rule: AutoTagRuleEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val condition = when (rule.matchKind) {
                RuleMatchKind.SOURCE -> "Source contains “${rule.matchValue}”"
                RuleMatchKind.TYPE -> "Added via ${typeLabel(rule.matchValue)}"
            }
            Text(condition, color = if (rule.enabled) Ink else Muted, fontSize = 14.sp)
            Text(
                rule.tags().joinToString(" ") { "#$it" },
                color = if (rule.enabled) Brass else Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        TextButton(onClick = onDelete) { Text("Delete", color = Danger, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRuleDialog(
    existingTags: List<TagEntity>,
    onAdd: (RuleMatchKind, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(RuleMatchKind.SOURCE) }
    var sourceValue by remember { mutableStateOf("") }
    var typeValue by remember { mutableStateOf(SourceType.CAMERA.name) }
    val picked = remember { mutableStateListOf<String>() }
    var newTag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rule") },
        text = {
            Column {
                Text("When…", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilterChip(
                        selected = kind == RuleMatchKind.SOURCE,
                        onClick = { kind = RuleMatchKind.SOURCE },
                        label = { Text("Source name") },
                    )
                    FilterChip(
                        selected = kind == RuleMatchKind.TYPE,
                        onClick = { kind = RuleMatchKind.TYPE },
                        label = { Text("How it was added") },
                    )
                }

                if (kind == RuleMatchKind.SOURCE) {
                    OutlinedTextField(
                        value = sourceValue,
                        onValueChange = { sourceValue = it },
                        placeholder = { Text("e.g. instagram") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        "Matches the app name, package, or website.",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        TYPE_CHOICES.forEach { (type, label) ->
                            FilterChip(
                                selected = typeValue == type.name,
                                onClick = { typeValue = type.name },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                Text("Apply tags", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                if (existingTags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        existingTags.take(12).forEach { tag ->
                            val on = picked.contains(tag.name)
                            FilterChip(
                                selected = on,
                                onClick = { if (on) picked.remove(tag.name) else picked.add(tag.name) },
                                label = { Text("#${tag.name}") },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("New tag(s), comma-separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = if (kind == RuleMatchKind.SOURCE) sourceValue else typeValue
                val allTags = (picked + newTag.split(",")).map { it.trim() }.filter { it.isNotEmpty() }
                onAdd(kind, value, allTags)
            }) { Text("Add", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
