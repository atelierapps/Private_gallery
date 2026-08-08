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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

private val Bg = Color(0xFF0E1113)
private val Surface = Color(0xFF171C20)
private val Brass = Color(0xFFD8B463)
private val Muted = Color(0xFF8A969E)
private val Ink = Color(0xFFE9EEF0)

/**
 * The importer UI (spec §4, §4.1, §15.5): two tabs (device gallery / folder), a
 * multi-select grid, and a bottom bar with the delete-originals switch (default
 * OFF) and the import action. A progress overlay covers the encrypt pass.
 */
@Composable
fun ImportScreen(
    tab: ImportTab,
    items: List<DeviceMedia>,
    selected: Set<Uri>,
    deleteOriginals: Boolean,
    importing: Boolean,
    progress: ImportProgress,
    onSelectDeviceTab: () -> Unit,
    onPickFolder: () -> Unit,
    onToggle: (Uri) -> Unit,
    onSetDelete: (Boolean) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(count = selected.size, onCancel = onCancel)
            Tabs(tab, onSelectDeviceTab, onPickFolder)

            if (tab == ImportTab.FOLDER && items.isEmpty()) {
                FolderEmpty(onPickFolder, Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        PickTile(item, item.uri in selected, onToggle)
                    }
                }
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
}

@Composable
private fun TopBar(count: Int, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
        Text(
            if (count == 0) "Select media" else "$count selected",
            color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

@Composable
private fun Tabs(tab: ImportTab, onDevice: () -> Unit, onFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(11.dp))
            .background(Surface),
    ) {
        TabButton("Photos & videos", tab == ImportTab.DEVICE, Modifier.weight(1f), onDevice)
        TabButton("From a folder", tab == ImportTab.FOLDER, Modifier.weight(1f), onFolder)
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
        Text(label, color = if (selected) Color(0xFF1A1509) else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun PickTile(item: DeviceMedia, selected: Boolean, onToggle: (Uri) -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1B2126))
            .clickable { onToggle(item.uri) }
            .then(if (selected) Modifier.border(2.5.dp, Brass, RoundedCornerShape(2.dp)) else Modifier),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).clip(CircleShape)
                    .background(Brass),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color(0xFF1A1509), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FolderEmpty(onPickFolder: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Pick a folder to import from.\nCopies are encrypted in; nothing is left in your gallery.",
                color = Muted, fontSize = 14.sp,
            )
            Button(onClick = onPickFolder) { Text("Choose folder") }
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
    Column(Modifier.fillMaxWidth().background(Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Delete originals after import", color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
        Modifier.fillMaxSize().background(Color(0xCC06080A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Brass)
            Text("Encrypting ${progress.done} / ${progress.total}", color = Ink, fontSize = 14.sp)
        }
    }
}
