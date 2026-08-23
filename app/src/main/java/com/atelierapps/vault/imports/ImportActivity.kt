package com.atelierapps.vault.imports

import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Hosts the importer (spec §4, §4.1). Owns the read-media permission request and
 * — after verified imports — the batched `createDeleteRequest` dialog. Folder
 * browsing is MediaStore-based (no SAF), so there's no tree-picker to block.
 */
class ImportActivity : ComponentActivity() {

    private val vm: ImportViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            Log.i("VaultImport", "delete dialog result=${result.resultCode}") // -1 = OK, 0 = cancelled
            vm.onDeviceDeleteFinished()
        }

    // SAF multi-file picker — reaches cloud providers, Downloads, any folder.
    private val filesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            // The import is finished by a background worker, which may run long
            // after this screen is gone — persist the read grant so it can still
            // open the file. Best-effort: some providers refuse.
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            vm.importDocumentUris(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
        else permissionLauncher.launch(MediaPermissions.required())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val tab by vm.tab.collectAsState()
                val items by vm.items.collectAsState()
                val folders by vm.folders.collectAsState()
                val currentFolder by vm.currentFolder.collectAsState()
                val selected by vm.selected.collectAsState()
                val typeFilter by vm.typeFilter.collectAsState()
                val deleteOriginals by vm.deleteOriginals.collectAsState()
                val importing by vm.importing.collectAsState()
                val progress by vm.progress.collectAsState()
                val finished by vm.finished.collectAsState()
                val pendingDelete by vm.pendingDeviceDelete.collectAsState()
                val summary by vm.summary.collectAsState()

                LaunchedEffect(finished) { if (finished) finish() }
                LaunchedEffect(pendingDelete) { launchDeleteRequest(pendingDelete) }

                ImportScreen(
                    tab = tab,
                    items = items,
                    folders = folders,
                    currentFolder = currentFolder,
                    selected = selected,
                    typeFilter = typeFilter,
                    deleteOriginals = deleteOriginals,
                    importing = importing,
                    progress = progress,
                    summary = summary,
                    onSelectDeviceTab = {
                        if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
                        else permissionLauncher.launch(MediaPermissions.required())
                    },
                    onSelectFolderTab = {
                        if (MediaPermissions.canQuery(this)) vm.selectFolderTab()
                        else permissionLauncher.launch(MediaPermissions.required())
                    },
                    onOpenFolder = vm::openFolder,
                    onBackToFolders = vm::backToFolders,
                    onToggle = vm::toggle,
                    onSetType = vm::setType,
                    onSetDelete = vm::setDeleteOriginals,
                    onImport = vm::startImport,
                    onPickFiles = { filesLauncher.launch(arrayOf("image/*", "video/*")) },
                    onDismissSummary = vm::dismissSummary,
                    onCancel = { finish() },
                )
            }
        }
    }

    /** Launch the system delete confirmation. Crash-proof: on any failure, keep originals and finish. */
    private fun launchDeleteRequest(uris: List<android.net.Uri>?) {
        val toDelete = uris ?: return
        try {
            Toast.makeText(this, "Requesting deletion of ${toDelete.size} original(s)…", Toast.LENGTH_SHORT).show()
            Log.i("VaultImport", "launching createDeleteRequest for ${toDelete.size} uris: ${toDelete.take(3)}")
            val request = MediaStore.createDeleteRequest(contentResolver, toDelete)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (t: Throwable) {
            Toast.makeText(this, "Delete blocked: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            Log.e("VaultImport", "createDeleteRequest failed; keeping originals", t)
            vm.onDeviceDeleteFinished()
        }
    }
}
