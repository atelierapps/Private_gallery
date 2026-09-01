package com.atelierapps.vault.ui.duplicates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.MediaWithTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copies of one thing. [keeperId] is the one that survives if you delete the rest. */
data class DuplicateGroup(
    val hash: String,
    val copies: List<MediaWithTags>,
    val keeperId: String,
    val keepAll: Boolean,
) {
    val extras: List<MediaWithTags> get() = copies.filter { it.media.id != keeperId }
    val reclaimable: Long get() = if (keepAll) 0L else extras.sumOf { it.media.sizeBytes }
}

/** One group's deviation from the defaults: a different keeper, or skip it entirely. */
private data class Choice(val keeperId: String? = null, val keepAll: Boolean = false)

/**
 * Finds items whose bytes are identical.
 *
 * The vault dedups on import, but only against *live* items — deliberately, so
 * re-importing something you trashed brings it back rather than silently
 * vanishing into the bin. The gap that leaves is the one that bites: restore a
 * backup while the same files sit in the recycle bin, then restore the bin as
 * well, and you have two live copies of everything and no way to tell them
 * apart by looking.
 *
 * Grouping by the content hash already on every row makes this exact rather
 * than a guess: the same hash means the same bytes, not a similar-looking
 * picture. Nothing here is heuristic, so nothing here can be wrong.
 */
class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    val working = MutableStateFlow(false)

    private val choices = MutableStateFlow<Map<String, Choice>>(emptyMap())

    val groups: StateFlow<List<DuplicateGroup>> =
        combine(repo.observeAll(), choices) { list, picked -> build(list, picked) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun build(list: List<MediaWithTags>, picked: Map<String, Choice>): List<DuplicateGroup> =
        list.asSequence()
            .filter { it.media.contentHash != null }
            .groupBy { it.media.contentHash!! }
            .asSequence()
            .filter { it.value.size > 1 }
            .map { (hash, copies) ->
                // Oldest first, and the oldest is the default keeper: it has been
                // in the library longest, so it is the one albums and tags are
                // most likely already attached to.
                val ordered = copies.sortedBy { it.media.importedAtMillis }
                val choice = picked[hash]
                DuplicateGroup(
                    hash = hash,
                    copies = ordered,
                    keeperId = choice?.keeperId?.takeIf { id -> ordered.any { it.media.id == id } }
                        ?: ordered.first().media.id,
                    keepAll = choice?.keepAll ?: false,
                )
            }
            .sortedByDescending { it.copies.size }
            .toList()

    fun chooseKeeper(hash: String, id: String) {
        val current = choices.value[hash] ?: Choice()
        choices.value = choices.value + (hash to current.copy(keeperId = id))
    }

    fun toggleKeepAll(hash: String) {
        val current = choices.value[hash] ?: Choice()
        choices.value = choices.value + (hash to current.copy(keepAll = !current.keepAll))
    }

    /** Bin every extra in every group not marked keep-all. */
    fun deleteExtras(onDone: (Int) -> Unit = {}) {
        val ids = groups.value.filterNot { it.keepAll }.flatMap { it.extras }.map { it.media.id }
        if (ids.isEmpty()) return
        working.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                // Soft delete, like every other delete here. Identical bytes or
                // not, thirty days to change your mind costs nothing.
                ids.forEach { repo.trashMedia(it, now) }
            }
            working.value = false
            onDone(ids.size)
        }
    }
}
