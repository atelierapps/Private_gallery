package com.atelierapps.vault.ui.edit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.MediaWithTags
import com.atelierapps.vault.media.ImageEditor
import com.atelierapps.vault.media.NormalisedRect
import com.atelierapps.vault.ui.lock.FinishOnLock
import com.atelierapps.vault.ui.theme.VaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rotate and crop one item. FLAG_SECURE like every other screen. */
class ImageEditActivity : ComponentActivity() {

    private val vm: ImageEditViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        vm.load(id)

        setContent {
            FinishOnLock { finish() }
            VaultTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsState()
                    ImageEditScreen(
                        state = state,
                        onRotate = vm::rotate,
                        onCrop = vm::setCrop,
                        onSave = { replace ->
                            vm.save(replace) { ok ->
                                Toast.makeText(
                                    this,
                                    if (ok) "Saved" else "Couldn't save that edit",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) finish()
                            }
                        },
                        onClose = { finish() },
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "media_id"

        fun intent(context: Context, id: String): Intent =
            Intent(context, ImageEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

class ImageEditViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val repo = VaultGraph.repository(app)

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state

    /** Full-size pixels, held only for the save; the screen draws the preview. */
    private var source: Bitmap? = null
    private var item: MediaWithTags? = null

    fun load(id: String) {
        if (item != null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val entity = repo.allMedia().firstOrNull { it.media.id == id }
                entity to ImageEditor.decode(getApplication(), id)
            }
            item = loaded.first
            source = loaded.second
            _state.value = _state.value.copy(preview = previewOf(loaded.second, 0))
        }
    }

    fun rotate(degrees: Int) {
        val next = ((_state.value.rotation + degrees) % 360 + 360) % 360
        // Rotating changes which way is up, so a frame chosen against the old
        // orientation no longer means anything — start the crop over.
        _state.value = _state.value.copy(
            rotation = next,
            crop = NormalisedRect.WHOLE,
            preview = previewOf(source, next),
        )
    }

    fun setCrop(rect: NormalisedRect) {
        _state.value = _state.value.copy(crop = rect)
    }

    fun save(replace: Boolean, onDone: (Boolean) -> Unit) {
        val original = item
        val full = source
        if (original == null || full == null) {
            onDone(false)
            return
        }
        val current = _state.value
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            val newId = withContext(Dispatchers.IO) {
                val edited = ImageEditor.transform(full, current.rotation, current.crop)
                ImageEditor.save(getApplication(), original, edited, replace)
            }
            _state.value = _state.value.copy(saving = false)
            onDone(newId != null)
        }
    }

    /**
     * A screen-sized copy to draw and rotate. Rotating a 12-megapixel bitmap on
     * every tap would allocate tens of megabytes for pixels nobody can see; the
     * full-size one is touched exactly once, at save.
     */
    private fun previewOf(source: Bitmap?, rotation: Int): Bitmap? {
        val bitmap = source ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled =
            if (longest <= PREVIEW_MAX) {
                bitmap
            } else {
                val factor = PREVIEW_MAX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * factor).toInt().coerceAtLeast(1),
                    (bitmap.height * factor).toInt().coerceAtLeast(1),
                    true,
                )
            }
        if (rotation % 360 == 0) return scaled
        return Bitmap.createBitmap(
            scaled, 0, 0, scaled.width, scaled.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
    }

    override fun onCleared() {
        super.onCleared()
        source = null
    }

    private companion object {
        const val PREVIEW_MAX = 1600
    }
}
