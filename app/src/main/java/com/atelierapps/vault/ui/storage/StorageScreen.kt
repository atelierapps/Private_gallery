package com.atelierapps.vault.ui.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.HeaderAction
import com.atelierapps.vault.ui.theme.Hairline
import androidx.compose.material3.MaterialTheme
import com.atelierapps.vault.ui.theme.Danger
import com.atelierapps.vault.ui.theme.FailureList
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

@Composable
fun StorageScreen(vm: StorageViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val stats by vm.stats.collectAsState()
    val loading by vm.loading.collectAsState()
    val check by vm.check.collectAsState()

    // A full check reads every file and can take minutes; the screen sleeping
    // partway would leave it to the phone's mercy in the background.
    val view = LocalView.current
    DisposableEffect(check.running) {
        view.keepScreenOn = check.running
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Storage", onClose) {
            HeaderAction("Refresh", vm::refresh)
        }

        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brass)
            }
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StorageCard("Library check") { LibraryCheck(check, vm::runCheck) }

            StorageCard("On disk") {
                Text(
                    formatBytes(stats.totalBytes),
                    color = Ink, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Encrypted size of everything stored here",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                Breakdown(stats)
            }

            StorageCard("Library") {
                StatRow("Photos", "${stats.photos}")
                StatRow("Videos", "${stats.videos}")
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
                StatRow("Albums", "${stats.albums}")
                StatRow("Tags", "${stats.tags}")
            }

            StorageCard("Recycle bin") {
                StatRow("Items", "${stats.trashCount}")
                StatRow("Space held", formatBytes(stats.trashBytes))
                if (stats.trashCount > 0) {
                    Text(
                        "Deleted items keep their space until they're purged — " +
                            "emptying the bin frees it immediately.",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Is anything actually damaged? The one question "this won't play" can't answer
 * by itself, because a file the player can't handle and a file that has been
 * corrupted look exactly alike on screen.
 */
@Composable
private fun LibraryCheck(check: CheckState, onRun: () -> Unit) {
    when {
        check.running -> {
            Text(
                "Checking " + check.done + " of " + check.total,
                color = Ink, style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { if (check.total == 0) 0f else check.done.toFloat() / check.total },
                color = Brass,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            if (check.problems.isNotEmpty()) {
                Text(
                    "" + check.problems.size + " problem(s) so far",
                    color = Danger, style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Keep this screen open — leaving it stops the check.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        check.finished -> {
            Text(
                if (check.problems.isEmpty()) {
                    "All " + check.total + " items are intact"
                } else {
                    "" + check.problems.size + " of " + check.total + " need attention"
                },
                color = if (check.problems.isEmpty()) Ink else Danger,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (check.problems.isEmpty()) {
                    "Every file decrypts to exactly the bytes that were imported. " +
                        "If something won't play, the file isn't damaged — the player " +
                        "will say why when it fails."
                } else {
                    "Each one is named below with what is wrong. Anything not listed " +
                        "decrypts to exactly what was imported."
                },
                color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            if (check.sizeOnly > 0) {
                Text(
                    "" + check.sizeOnly + " item(s) predate content checks: their size " +
                        "is right, but their content couldn't be compared.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            FailureList(check.problems)
            TextButton(onClick = onRun) { Text("Check again", color = Brass) }
        }
        else -> {
            Text(
                "Decrypts every item and compares it with the fingerprint taken when " +
                    "it was imported. A match means the file is exactly what came in.\n\n" +
                    "Reads every file in full, so a large library takes a few minutes.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Button(onClick = onRun) { Text("Check library") }
        }
    }
}

/** A single proportional bar: live media / thumbnails / bin / temporary. */
@Composable
private fun Breakdown(stats: StorageStats) {
    val total = stats.totalBytes.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Segment(stats.liveBytes, total, Brass)
        Segment(stats.thumbBytes, total, Color(0xFF7FA88B))
        Segment(stats.trashBytes, total, Danger)
        Segment(stats.tempBytes, total, Color(0xFF4A78C4))
    }
    Column(Modifier.padding(top = 10.dp)) {
        LegendRow("Media", stats.liveBytes, Brass)
        LegendRow("Thumbnails", stats.thumbBytes, Color(0xFF7FA88B))
        LegendRow("Recycle bin", stats.trashBytes, Danger)
        LegendRow("Temporary", stats.tempBytes, Color(0xFF4A78C4))
    }
}

@Composable
private fun RowScope.Segment(bytes: Long, total: Long, color: Color) {
    val fraction = (bytes.toFloat() / total).coerceIn(0f, 1f)
    if (fraction <= 0f) return
    Box(Modifier.weight(fraction).fillMaxHeight().background(color))
}

@Composable
private fun LegendRow(label: String, bytes: Long, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(10.dp).width(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(formatBytes(bytes), color = Ink, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StorageCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(),
            color = Brass, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceHigh).padding(16.dp),
        ) { content() }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}
