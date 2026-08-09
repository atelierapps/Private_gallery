package com.atelierapps.vault.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import com.atelierapps.vault.data.entity.RuleMatchKind
import com.atelierapps.vault.data.entity.TagEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Backs the auto-tag rules screen (spec §7). */
class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val rules: StateFlow<List<AutoTagRuleEntity>> =
        repo.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRule(kind: RuleMatchKind, value: String, tagNames: List<String>) {
        val cleanTags = tagNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (value.isBlank() || cleanTags.isEmpty()) return
        viewModelScope.launch {
            repo.upsertRule(
                AutoTagRuleEntity(
                    id = UUID.randomUUID().toString(),
                    matchKind = kind,
                    matchValue = value.trim(),
                    tagNames = cleanTags.joinToString(","),
                    enabled = true,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setRuleEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteRule(id) }
    }
}
