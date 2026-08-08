package com.atelierapps.vault.imports

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Hosts the importer (spec §4, §4.1). Owns the three system interactions the
 * pipeline needs: the read-media permission request, the SAF folder picker
 * (persisted read/write grant), and — after verified imports — the batched
 * `createDeleteRequest` dialog for device originals.
 */
class ImportActivity : ComponentActivity() {

    private val vm: ImportViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.onFolderPicked(uri)
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // ok or cancel — either way the import is done; vault copies are kept.
            vm.onDeviceDeleteFinished()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
        else permissionLauncher.launch(MediaPermissions.required())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val tab by vm.tab.collectAsState()
                val items by vm.items.collectAsState()
                val selected by vm.selected.collectAsState()
                val deleteOriginals by vm.deleteOriginals.collectAsState()
                val importing by vm.importing.collectAsState()
                val progress by vm.progress.collectAsState()
                val finished by vm.finished.collectAsState()
                val pendingDelete by vm.pendingDeviceDelete.collectAsState()

                LaunchedEffect(finished) { if (finished) finish() }
                LaunchedEffect(pendingDelete) {
                    pendingDelete?.let { uris ->
                        val request = MediaStore.createDeleteRequest(contentResolver, uris)
                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }

                ImportScreen(
                    tab = tab,
                    items = items,
                    selected = selected,
                    deleteOriginals = deleteOriginals,
                    importing = importing,
                    progress = progress,
                    onSelectDeviceTab = {
                        if (MediaPermissions.canQuery(this)) vm.selectDeviceTab()
                        else permissionLauncher.launch(MediaPermissions.required())
                    },
                    onPickFolder = { folderLauncher.launch(null) },
                    onToggle = vm::toggle,
                    onSetDelete = vm::setDeleteOriginals,
                    onImport = vm::startImport,
                    onCancel = { finish() },
                )
            }
        }
    }
}
