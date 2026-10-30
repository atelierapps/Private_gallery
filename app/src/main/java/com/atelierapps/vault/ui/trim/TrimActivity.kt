package com.atelierapps.vault.ui.trim

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
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
import com.atelierapps.vault.media.TransferWake
import com.atelierapps.vault.media.VideoTrimmer
import com.atelierapps.vault.ui.lock.FinishOnLock
import com.atelierapps.vault.ui.theme.VaultTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keep one stretch of a video. FLAG_SECURE like every other screen. */
@UnstableApi
class TrimActivity : ComponentActivity() {

    private val vm: TrimViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        // Only on first creation, so a rotation keeps whichever mode was picked.
        if (savedInstanceState == null) vm.setMode(intent.getBooleanExtra(EXTRA_THUMBNAIL, false))
        vm.load(id)

        setContent {
            // The auto-lock waits out a running trim (see TrimViewModel.held),
            // so this lands after it. A lock that comes anyway clears the view
            // model and cancels the run; the half-written temp file goes then.
            FinishOnLock { finish() }
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsState()
                    TrimScreen(
                        state = state,
                        onRange = vm::setRange,
                        onReplace = vm::setReplace,
                        onTrim = {
                            vm.trim { replaced ->
                                Toast.makeText(
                                    this,
                                    if (replaced) "Done. The original is in Recently deleted"
                                    else "Done. The original is kept",
                                    Toast.LENGTH_LONG,
                                ).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        },
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                        onDuration = vm::setDuration,
                        onMode = vm::setMode,
                        onAddPart = vm::addPart,
                        onRemovePart = vm::removePart,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "media_id"
        private const val EXTRA_THUMBNAIL = "thumbnail_mode"

        /** [thumbnail] opens on choosing a thumbnail rather than trimming. */
        fun intent(context: Context, id: String, thumbnail: Boolean = false): Intent =
            Intent(context, TrimActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_THUMBNAIL, thumbnail)
    }
}

data class TrimState(
    val item: MediaWithTags? = null,
    /** Known once the clip has loaded; 0 until then. */
    val durationMs: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    /** Bin the original once the trimmed copy checks out. On by default: saving space is the point. */
    val replace: Boolean = true,
    val running: Boolean = false,
    /** Choosing a thumbnail rather than trimming: two jobs, one preview. */
    val thumbnailMode: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    /** A split: stretches to keep as separate items. Empty means a plain trim. */
    val parts: List<Pair<Long, Long>> = emptyList(),
    /** Which part is being cut, 1-based, while a split runs. */
    val runningPart: Int = 0,
)

@UnstableApi
class TrimViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TrimState())
    val state: StateFlow<TrimState> = _state
    private var job: Job? = null

    fun load(id: String) {
        if (_state.value.item != null) return
        viewModelScope.launch {
            val item = VaultGraph.repository(getApplication()).mediaById(id) ?: return@launch
            val duration = item.media.durationMillis ?: 0L
            _state.value = _state.value.copy(item = item, durationMs = duration, startMs = 0L, endMs = duration)
        }
    }

    /** The player reports the real length if the stored one was missing. */
    fun setDuration(ms: Long) {
        val s = _state.value
        if (ms <= 0L || s.durationMs > 0L) return
        _state.value = s.copy(durationMs = ms, endMs = ms)
    }

    fun setRange(startMs: Long, endMs: Long) {
        if (_state.value.running) return
        _state.value = _state.value.copy(startMs = startMs, endMs = endMs, error = null)
    }

    fun setMode(thumbnail: Boolean) {
        if (_state.value.running) return
        _state.value = _state.value.copy(thumbnailMode = thumbnail, error = null)
    }

    fun setReplace(value: Boolean) {
        if (_state.value.running) return
        _state.value = _state.value.copy(replace = value)
    }

    /** Add the current range as one part of a split. */
    fun addPart() {
        val s = _state.value
        if (s.running || s.endMs - s.startMs < VideoTrimmer.MIN_KEEP_MILLIS) return
        val part = s.startMs to s.endMs
        if (part in s.parts) return
        _state.value = s.copy(parts = (s.parts + part).sortedBy { it.first }, error = null)
    }

    fun removePart(part: Pair<Long, Long>) {
        if (_state.value.running) return
        _state.value = _state.value.copy(parts = _state.value.parts - part)
    }

    fun trim(onDone: (replaced: Boolean) -> Unit) {
        val s = _state.value
        val item = s.item ?: return
        if (s.running) return
        if (s.parts.isNotEmpty()) {
            split(s, item, onDone)
            return
        }
        _state.value = s.copy(running = true, progress = 0, error = null)
        job = viewModelScope.launch {
            val result = held {
                VideoTrimmer.trim(getApplication(), item, s.startMs, s.endMs, s.replace) { p ->
                    _state.value = _state.value.copy(progress = p)
                }
            }
            result.fold(
                onSuccess = { onDone(s.replace) },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        running = false,
                        error = "Nothing was changed: " + (e.message ?: "the trim failed"),
                    )
                },
            )
        }
    }

    /**
     * Cut every part as its own new item, one after another, and only then —
     * all of them saved and verified — bin the original if asked. One failure
     * stops the run and keeps the original: the parts that did land stay as
     * new items, and the screen says how many.
     */
    private fun split(s: TrimState, item: MediaWithTags, onDone: (replaced: Boolean) -> Unit) {
        _state.value = s.copy(running = true, progress = 0, runningPart = 1, error = null)
        job = viewModelScope.launch { held { runSplit(s, item, onDone) } }
    }

    private suspend fun runSplit(s: TrimState, item: MediaWithTags, onDone: (replaced: Boolean) -> Unit) {
        var saved = 0
        var failure: String? = null
        for ((i, part) in s.parts.withIndex()) {
            _state.value = _state.value.copy(runningPart = i + 1, progress = 0)
            val r = VideoTrimmer.trim(
                getApplication(), item, part.first, part.second, replace = false,
                onProgress = { p -> _state.value = _state.value.copy(progress = p) },
                part = i + 1,
            )
            if (r.isSuccess) {
                saved++
            } else {
                failure = r.exceptionOrNull()?.message ?: "it failed"
                break
            }
        }
        if (failure == null) {
            if (s.replace) VaultGraph.repository(getApplication()).trashMedia(item.media.id)
            onDone(s.replace)
        } else {
            _state.value = _state.value.copy(
                running = false,
                runningPart = 0,
                // The parts that landed are already in the library; don't
                // offer them again.
                parts = s.parts.drop(saved),
                error = (if (saved == 0) "Nothing was changed" else "$saved of ${s.parts.size} parts saved") +
                    ": part ${saved + 1} failed — $failure. The original is kept.",
            )
        }
    }

    /**
     * Runs [block] as a transfer. Leaving the app mid-trim let the auto-lock
     * land underneath it, and locking finishes this screen, which cancelled the
     * run without a word. Held, the auto-lock waits for the run to end (as it
     * does for a backup) and the process keeps foreground priority with the
     * screen off; the lock lands once it's done.
     */
    private suspend fun <T> held(block: suspend () -> T): T {
        TransferWake.acquire(getApplication())
        try {
            return block()
        } finally {
            TransferWake.release()
        }
    }
}
