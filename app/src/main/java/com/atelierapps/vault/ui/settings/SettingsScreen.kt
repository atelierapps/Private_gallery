package com.atelierapps.vault.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelierapps.vault.session.DisplayPrefs
import com.atelierapps.vault.session.LockPrefs
import com.atelierapps.vault.session.VideoPrefs

private val Bg = Color(0xFF0E1113)
private val Card = Color(0xFF171C20)
private val Ink = Color(0xFFE9EEF0)
private val Muted = Color(0xFF8A969E)
private val Brass = Color(0xFFD8B463)
private val BrassInk = Color(0xFF1A1509)

@Composable
fun SettingsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var autoplay by remember { mutableStateOf(VideoPrefs.autoplay(context)) }
    var dateHeaders by remember { mutableStateOf(DisplayPrefs.dateHeaders(context)) }
    var columns by remember { mutableIntStateOf(DisplayPrefs.columns(context)) }
    var lockDelay by remember { mutableStateOf(LockPrefs.current(context)) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    Column(modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("✕", color = Ink, fontSize = 16.sp) }
            Text(
                "Settings",
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("Playback") {
                ToggleRow(
                    title = "Autoplay videos",
                    subtitle = "Start playing as soon as a video opens",
                    checked = autoplay,
                    onCheckedChange = { autoplay = it; VideoPrefs.setAutoplay(context, it) },
                )
            }

            SettingsCard("Display") {
                ToggleRow(
                    title = "Date sections",
                    subtitle = "Group the grid by Today / This week / …",
                    checked = dateHeaders,
                    onCheckedChange = { dateHeaders = it; DisplayPrefs.setDateHeaders(context, it) },
                )
                HorizontalDivider(color = Color(0xFF20272C), modifier = Modifier.padding(vertical = 10.dp))
                Text("Grid columns", color = Ink, fontSize = 15.sp)
                Text(
                    "Default tile density (pinch on the grid still adjusts live)",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in DisplayPrefs.MIN_COLUMNS..DisplayPrefs.MAX_COLUMNS) {
                        NumberChip(n, n == columns) { columns = n; DisplayPrefs.setColumns(context, n) }
                    }
                }
            }

            SettingsCard("Security") {
                Text("Auto-lock", color = Ink, fontSize = 15.sp)
                Text(
                    "Lock the vault after it's been in the background",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                LockPrefs.Delay.entries.forEach { d ->
                    RadioRow(d.label, d == lockDelay) { lockDelay = d; LockPrefs.set(context, d) }
                }
            }

            SettingsCard("About") {
                Text("Link", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (version != null) {
                    Text("Version $version", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    "No internet access. Everything stays encrypted on this device — " +
                        "no cloud, no accounts, no analytics.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title.uppercase(),
            color = Brass, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(16.dp),
        ) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BrassInk,
                checkedTrackColor = Brass,
            ),
        )
    }
}

@Composable
private fun NumberChip(n: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brass else Color(0xFF232A30))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = if (selected) BrassInk else Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (selected) Brass else Color(0xFF2A3238)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", color = BrassInk, fontSize = 11.sp) }
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
