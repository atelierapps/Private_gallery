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
import com.atelierapps.vault.ui.theme.TagPicker
import com.atelierapps.vault.ui.theme.Muted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.ScreenCaption
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Hairline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon

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
            ScreenHeader("Auto-tag rules", onClose)
            ScreenCaption(
                "Tags are applied automatically as items are saved, based on where " +
                    "they came from. New rules apply to items saved from now on.",
            )

            if (rules.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No rules yet.\nTap + to add one.",
                        color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
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
                        HorizontalDivider(color = Hairline)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            containerColor = Brass,
            contentColor = BrassInk,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Outlined.Add, contentDescription = "New rule") }
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
            Text(condition, color = if (rule.enabled) Ink else Muted, style = MaterialTheme.typography.bodyMedium)
            Text(
                rule.tags().joinToString(" ") { "#$it" },
                color = if (rule.enabled) Brass else Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        TextButton(onClick = onDelete) { Text("Delete", color = Danger, style = MaterialTheme.typography.bodySmall) }
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
                Text("When…", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                        color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp),
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

                Text("Apply tags", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                TagPicker(
                    tags = existingTags,
                    isPicked = { name -> picked.any { it.equals(name, ignoreCase = true) } },
                    onToggle = { name ->
                        if (picked.any { it.equals(name, ignoreCase = true) }) {
                            picked.removeAll { it.equals(name, ignoreCase = true) }
                        } else {
                            picked.add(name)
                        }
                    },
                    maxHeight = 180.dp,
                )
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
