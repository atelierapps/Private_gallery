package com.atelierapps.vault.imports

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.atelierapps.vault.filter.MediaTypeFilter
import com.atelierapps.vault.ui.image.DeviceThumb
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.PlayArrow
import com.atelierapps.vault.ui.theme.Scrim
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.atelierapps.vault.ui.theme.Danger

/**
 * The importer UI (spec §4, §4.1, §15.5): tabs (all media / by folder), a
 * Photos·Videos type filter, a multi-select grid, and a bottom bar with the
 * delete-originals switch (default OFF) and the import action.
 */
@Composable
fun ImportScreen(
    tab: ImportTab,
    items: List<DeviceMedia>,
    folders: List<MediaFolder>,
    currentFolder: MediaFolder?,
    selected: Set<Uri>,
    typeFilter: MediaTypeFilter,
    deleteOriginals: Boolean,
    importing: Boolean,
    progress: ImportProgress,
    summary: ImportProgress?,
    onSelectDeviceTab: () -> Unit,
    onSelectFolderTab: () -> Unit,
    onOpenFolder: (MediaFolder) -> Unit,
    onBackToFolders: () -> Unit,
    onToggle: (Uri) -> Unit,
    onSetType: (MediaTypeFilter) -> Unit,
    onSetDelete: (Boolean) -> Unit,
    onImport: () -> Unit,
    onPickFiles: () -> Unit,
    onDismissSummary: () -> Unit,
    onCancel: () -> Unit,
) {
    val browsingFolders = tab == ImportTab.FOLDER && currentFolder == null

    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(count = selected.size, onPickFiles = onPickFiles, onCancel = onCancel)
            Tabs(tab, onSelectDeviceTab, onSelectFolderTab)

            if (browsingFolders) {
                FolderGrid(folders, onOpenFolder, Modifier.weight(1f))
            } else {
                if (tab == ImportTab.FOLDER && currentFolder != null) {
                    FolderHeader(currentFolder, onBackToFolders)
                }
                TypeFilterRow(typeFilter, onSetType)
                MediaGrid(items, selected, onToggle, Modifier.weight(1f))
            }

            BottomBar(
                count = selected.size,
                deleteOriginals = deleteOriginals,
                enabled = selected.isNotEmpty() && !importing,
                onSetDelete = onSetDelete,
                onImport = onImport,
            )
        }

        if (importing) ImportingOverlay(progress)
    }

    if (summary != null) ImportSummaryDialog(summary, onDismissSummary)
}

/**
 * What actually landed. Dedup silently skips content already in the vault, so a
 * raw "imported N" would overcount; this reports the skips explicitly.
 */
@Composable
private fun ImportSummaryDialog(summary: ImportProgress, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import complete") },
        text = {
            Column {
                Text("${summary.imported} added to Link", color = Ink, style = MaterialTheme.typography.bodyLarge)
                if (summary.duplicates > 0) {
                    Text(
                        "${summary.duplicates} skipped — already in your library",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (summary.failed > 0) {
                    Text(
                        "${summary.failed} couldn't be read",
                        color = Danger, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Brass) } },
    )
}

@Composable
private fun TopBar(count: Int, onPickFiles: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
        Text(
            if (count == 0) "Select media" else "$count selected",
            color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = onPickFiles) { Text("From files…", color = Brass) }
    }
}

@Composable
private fun Tabs(tab: ImportTab, onDevice: () -> Unit, onFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(11.dp)).background(SurfaceHigh),
    ) {
        TabButton("All media", tab == ImportTab.DEVICE, Modifier.weight(1f), onDevice)
        TabButton("By folder", tab == ImportTab.FOLDER, Modifier.weight(1f), onFolder)
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(3.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brass else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) BrassInk else Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TypeFilterRow(current: MediaTypeFilter, onSetType: (MediaTypeFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypeChip("All", current == MediaTypeFilter.ALL) { onSetType(MediaTypeFilter.ALL) }
        TypeChip("Photos", current == MediaTypeFilter.IMAGE) { onSetType(MediaTypeFilter.IMAGE) }
        TypeChip("Videos", current == MediaTypeFilter.VIDEO) { onSetType(MediaTypeFilter.VIDEO) }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun FolderHeader(folder: MediaFolder, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = null,
            tint = Brass,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "Folders",
            color = Brass,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            "  ·  ${folder.name} (${folder.count})",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FolderGrid(folders: List<MediaFolder>, onOpen: (MediaFolder) -> Unit, modifier: Modifier) {
    if (folders.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No folders found.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.bucketId }) { folder ->
            Column(Modifier.clickable { onOpen(folder) }) {
                AsyncImage(
                    model = DeviceThumb(folder.coverUri),
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)).background(SurfaceHigh),
                )
                Text(
                    folder.name, color = Ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                )
                Text("${folder.count} items", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MediaGrid(
    items: List<DeviceMedia>,
    selected: Set<Uri>,
    onToggle: (Uri) -> Unit,
    modifier: Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            PickTile(item, item.uri in selected, onToggle)
        }
    }
}

@Composable
private fun PickTile(item: DeviceMedia, selected: Boolean, onToggle: (Uri) -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp)).background(SurfaceHigh)
            .clickable { onToggle(item.uri) }
            .then(if (selected) Modifier.border(2.5.dp, Brass, RoundedCornerShape(2.dp)) else Modifier),
    ) {
        AsyncImage(
            model = DeviceThumb(item.uri),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(15.dp),
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(Brass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, null, tint = BrassInk, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(
    count: Int,
    deleteOriginals: Boolean,
    enabled: Boolean,
    onSetDelete: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(SurfaceHigh).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Delete originals after import", color = Ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = deleteOriginals, onCheckedChange = onSetDelete)
        }
        Button(
            onClick = onImport,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(if (count == 0) "Import" else "Import $count ${if (count == 1) "item" else "items"}")
        }
    }
}

@Composable
private fun ImportingOverlay(progress: ImportProgress) {
    Box(
        Modifier.fillMaxSize().background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Brass)
            Text("Encrypting ${progress.done} / ${progress.total}", color = Ink, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Keeps running if you leave this screen",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
