package com.atelierapps.vault.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo
import com.atelierapps.vault.storage.VaultStorage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transparent share target — the primary daily flow (spec §5).
 *
 * Critical ordering (spec §5.1): the `ACTION_SEND` read grant dies when this
 * Activity finishes, so we open each InputStream and **spool it to an
 * app-private tmp file while still alive**, then hand the owned file to an
 * expedited [SaveMediaWorker]. No biometric prompt — saving uses the public key.
 */
class ShareReceiverActivity : ComponentActivity() {

    private lateinit var storage: VaultStorage
    private lateinit var source: SourceInfo
    private lateinit var incoming: List<Uri>
    private var spoolJob: Deferred<List<Spooled>>? = null

    private data class Spooled(
        val tempPath: String,
        val mimeType: String,
        val originalName: String,
        val dateTakenMillis: Long,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        storage = VaultGraph.storage(this)
        incoming = extractUris(intent)
        if (incoming.isEmpty()) { finish(); return }

        source = SourceAttribution.capture(referrer, intent, packageManager)

        // Spool immediately, while the grant is alive (§5.1). Kept as a Deferred
        // the Save action awaits — large videos may still be copying when the
        // sheet appears, which is why Save shows a brief "Saving…" state.
        spoolJob = lifecycleScope.async(Dispatchers.IO) {
            incoming.mapNotNull { runCatching { spool(it) }.getOrNull() }
        }

        setContent {
            SaveSheet(
                previewUri = incoming.first(),
                itemCount = incoming.size,
                source = source,
                loadTopTags = { VaultGraph.repository(this).topTags(6) },
                onSave = ::onSave,
                onDismiss = ::onDismiss,
            )
        }
    }

    private fun onSave(selectedTags: List<String>, onEnqueued: () -> Unit) {
        lifecycleScope.launch {
            val spooled = spoolJob?.await().orEmpty()
            spooled.forEach { s ->
                SaveMediaWorker.enqueue(
                    this@ShareReceiverActivity,
                    SaveRequest(
                        tempPath = s.tempPath,
                        mimeType = s.mimeType,
                        originalName = s.originalName,
                        dateTakenMillis = s.dateTakenMillis,
                        tagNames = selectedTags,
                        source = source,
                    ),
                )
            }
            onEnqueued()
            finish()
        }
    }

    private fun onDismiss() {
        // Discard spooled plaintext if the user backed out.
        lifecycleScope.launch {
            spoolJob?.await()?.forEach { File(it.tempPath).delete() }
            finish()
        }
    }

    private suspend fun spool(uri: Uri): Spooled = withContext(Dispatchers.IO) {
        val temp = storage.newTempFile()
        contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        Spooled(
            tempPath = temp.absolutePath,
            mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream",
            originalName = displayName(uri),
            dateTakenMillis = System.currentTimeMillis(),
        )
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "shared"
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { listOf(it) }.orEmpty()
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty()
        else -> emptyList()
    }
}
