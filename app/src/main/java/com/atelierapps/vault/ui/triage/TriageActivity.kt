package com.atelierapps.vault.ui.triage

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.ui.lock.FinishOnLock
import com.atelierapps.vault.ui.theme.VaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The untagged pile, one item at a time: tag it, keep it as it is, or bin it.
 *
 * Tagging forty fresh imports through the grid means finding each one, opening
 * it, opening the tag sheet, and coming back. Here the next one is already on
 * screen and the tags are already open. FLAG_SECURE like every other screen.
 */
@UnstableApi
class TriageActivity : ComponentActivity() {

    private val vm: TriageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        vm.load()

        setContent {
            FinishOnLock { finish() }
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsState()
                    val tags by vm.tags.collectAsState()
                    TriageScreen(
                        state = state,
                        allTags = tags,
                        onTogglePick = vm::togglePick,
                        onText = vm::setText,
                        onSaveTags = vm::saveTags,
                        onSkip = vm::skip,
                        onDelete = vm::delete,
                        onUndoDelete = vm::undoDelete,
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }
}

data class TriageState(
    val loaded: Boolean = false,
    /** Snapshot of the untagged pile when the screen opened, newest arrivals first. */
    val queue: List<MediaWithTags> = emptyList(),
    val index: Int = 0,
    val picked: Set<String> = emptySet(),
    val text: String = "",
    /** Tagged here this session, for the count at the end. */
    val tagged: Int = 0,
    val binned: Int = 0,
    /** The last item binned, while "Undo" is still offered. */
    val undoId: String? = null,
) {
    val current: MediaWithTags? get() = queue.getOrNull(index)
    val done: Boolean get() = loaded && index >= queue.size
}

class TriageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)
    private val _state = MutableStateFlow(TriageState())
    val state: StateFlow<TriageState> = _state

    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A snapshot, not a live query: an item tagged here would otherwise drop out
     * of the list under the index and shift everything after it by one.
     */
    fun load() {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val all = repo.observeAll().first()
            val untagged = all.filter { it.tags.isEmpty() }.sortedByDescending { it.media.importedAtMillis }
            _state.value = TriageState(loaded = true, queue = untagged)
        }
    }

    fun togglePick(name: String) {
        val s = _state.value
        val on = s.picked.any { it.equals(name, ignoreCase = true) }
        _state.value = s.copy(
            picked = if (on) s.picked.filterNot { it.equals(name, ignoreCase = true) }.toSet() else s.picked + name,
        )
    }

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value)
    }

    /** Apply what's picked (and typed), then move on. Nothing picked: same as skip. */
    fun saveTags() {
        val s = _state.value
        val item = s.current ?: return
        val names = (s.picked + s.text.split(","))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.isEmpty()) {
            skip()
            return
        }
        viewModelScope.launch {
            repo.addTags(item.media.id, names)
            advance(tagged = 1)
        }
    }

    fun skip() = advance()

    /** To Recently deleted, like everywhere else; undo is one tap until the next action. */
    fun delete() {
        val item = _state.value.current ?: return
        viewModelScope.launch {
            repo.trashMedia(item.media.id)
            advance(binned = 1, undoId = item.media.id)
        }
    }

    fun undoDelete() {
        val s = _state.value
        val id = s.undoId ?: return
        viewModelScope.launch {
            repo.restoreMedia(id)
            // Back to the item that was binned, so it can be dealt with properly.
            _state.value = s.copy(index = (s.index - 1).coerceAtLeast(0), binned = s.binned - 1, undoId = null)
        }
    }

    private fun advance(tagged: Int = 0, binned: Int = 0, undoId: String? = null) {
        val s = _state.value
        _state.value = s.copy(
            index = s.index + 1,
            picked = emptySet(),
            text = "",
            tagged = s.tagged + tagged,
            binned = s.binned + binned,
            undoId = undoId,
        )
    }
}
