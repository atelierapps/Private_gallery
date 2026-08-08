package com.atelierapps.vault.imports

import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
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
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // ok or cancel — either way the import is done; vault copies are kept.
            vm.onDeviceDeleteFinished()
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
                    onCancel = { finish() },
                )
            }
        }
    }

    /** Launch the system delete confirmation. Crash-proof: on any failure, keep originals and finish. */
    private fun launchDeleteRequest(uris: List<android.net.Uri>?) {
        val toDelete = uris ?: return
        try {
            val request = MediaStore.createDeleteRequest(contentResolver, toDelete)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (t: Throwable) {
            Log.e("ImportActivity", "createDeleteRequest failed; keeping originals", t)
            vm.onDeviceDeleteFinished()
        }
    }
}
