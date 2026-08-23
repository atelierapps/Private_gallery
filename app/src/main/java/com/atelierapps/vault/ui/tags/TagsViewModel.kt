package com.atelierapps.vault.ui.tags

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.db.TagUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the tag manager (spec §7): rename, merge, delete. */
class TagsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val tags: StateFlow<List<TagUsage>> =
        repo.observeTagUsage()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Non-null when a rename collided with an existing tag name. */
    val error = MutableStateFlow<String?>(null)

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            val ok = repo.renameTag(id, newName)
            if (!ok) error.value = "“${newName.trim()}” already exists — merge into it instead."
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteTag(id) }
    }

    fun merge(sourceId: String, targetId: String) {
        viewModelScope.launch { repo.mergeTags(sourceId, targetId) }
    }

    fun clearError() { error.value = null }
}
