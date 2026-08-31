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
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.BrassInk
import com.atelierapps.vault.ui.theme.Ink
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.ui.theme.SurfaceHigh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.atelierapps.vault.ui.theme.VaultIconButton
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import com.atelierapps.vault.ui.theme.ScreenHeader
import com.atelierapps.vault.ui.theme.Hairline
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.atelierapps.vault.session.AppDisguise
import com.atelierapps.vault.session.Disguise
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableFloatStateOf
import com.atelierapps.vault.session.HomeShortcut
import com.atelierapps.vault.session.LockButtonPrefs
import com.atelierapps.vault.ui.theme.Faint
import androidx.compose.runtime.collectAsState
import com.atelierapps.vault.session.BackupPrefs
import com.atelierapps.vault.ui.theme.Danger

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onStorage: () -> Unit,
    onBackUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var autoplay by remember { mutableStateOf(VideoPrefs.autoplay(context)) }
    var muted by remember { mutableStateOf(VideoPrefs.muted(context)) }
    var dateHeaders by remember { mutableStateOf(DisplayPrefs.dateHeaders(context)) }
    var columns by remember { mutableIntStateOf(DisplayPrefs.columns(context)) }
    var lockDelay by remember { mutableStateOf(LockPrefs.current(context)) }
    var disguise by remember { mutableStateOf(AppDisguise.current(context)) }
    var pickingDisguise by remember { mutableStateOf(false) }
    var namingShortcut by remember { mutableStateOf(false) }
    var lockButton by remember { mutableStateOf(LockButtonPrefs.enabled.value) }
    var lockOpacity by remember { mutableFloatStateOf(LockButtonPrefs.opacity.value) }
    val lastBackup by BackupPrefs.lastBackupAtMillis.collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    Column(modifier.fillMaxSize().background(Bg)) {
        ScreenHeader("Settings", onClose)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("Backup") {
                Text(
                    if (lastBackup == 0L) "Never backed up" else "Last backup ${backupDate(lastBackup)}",
                    color = if (lastBackup == 0L) Danger else Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Everything here lives in this app's private storage, and the key " +
                        "that decrypts it belongs to this install. Uninstalling the app " +
                        "deletes both — there is no cloud copy and no way to recover it " +
                        "afterwards, by any means. An export is the only copy that " +
                        "survives.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onBackUp).padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Back up now",
                        color = Brass,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Brass, modifier = Modifier.size(20.dp))
                }
            }

            SettingsCard("Playback") {
                ToggleRow(
                    title = "Autoplay videos",
                    subtitle = "Start playing as soon as a video opens",
                    checked = autoplay,
                    onCheckedChange = { autoplay = it; VideoPrefs.setAutoplay(context, it) },
                )
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
                ToggleRow(
                    title = "Mute videos",
                    subtitle = "Silences playback and disables the swipe volume gesture",
                    checked = muted,
                    onCheckedChange = { muted = it; VideoPrefs.setMuted(context, it) },
                )
            }

            SettingsCard("Display") {
                ToggleRow(
                    title = "Date sections",
                    subtitle = "Group the grid by Today / This week / …",
                    checked = dateHeaders,
                    onCheckedChange = { dateHeaders = it; DisplayPrefs.setDateHeaders(context, it) },
                )
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
                Text("Grid columns", color = Ink, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Default tile density (pinch on the grid still adjusts live)",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in DisplayPrefs.MIN_COLUMNS..DisplayPrefs.MAX_COLUMNS) {
                        NumberChip(n, n == columns) { columns = n; DisplayPrefs.setColumns(context, n) }
                    }
                }
            }

            SettingsCard("Disguise") {
                Row(
                    Modifier.fillMaxWidth().clickable { pickingDisguise = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DisguiseIcon(disguise, size = 40)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Home screen name & icon", color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Currently \u201c${disguise.label}\u201d",
                            color = Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                }
                Text(
                    "Changes what the launcher and your app list show. Your library, " +
                        "the encryption key and every setting are untouched — only the " +
                        "label on the icon changes.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )

                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    Modifier.fillMaxWidth().clickable { namingShortcut = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Any name you like", color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Add a home screen icon with a name you type yourself",
                            color = Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                }
            }

            SettingsCard("Security") {
                Text("Auto-lock", color = Ink, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Lock the vault after it's been in the background",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                LockPrefs.Delay.entries.forEach { d ->
                    RadioRow(d.label, d == lockDelay) { lockDelay = d; LockPrefs.set(context, d) }
                }

                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
                ToggleRow(
                    title = "Floating lock button",
                    subtitle = "A lock within thumb reach. Drag it anywhere; it fades " +
                        "away a couple of seconds after a photo or video opens",
                    checked = lockButton,
                    onCheckedChange = { lockButton = it; LockButtonPrefs.setEnabled(context, it) },
                )
                if (lockButton) {
                    Text(
                        "How visible",
                        color = Ink, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Faint enough to ignore, solid enough to find",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Slider(
                        value = lockOpacity,
                        onValueChange = { lockOpacity = it },
                        onValueChangeFinished = { LockButtonPrefs.setOpacity(context, lockOpacity) },
                        valueRange = LockButtonPrefs.MIN_OPACITY..LockButtonPrefs.MAX_OPACITY,
                    )
                }
            }

            SettingsCard("Storage") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onStorage),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Storage & stats", color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "What the vault holds and what it costs on disk",
                            color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                }
            }

            SettingsCard("About") {
                Text(
                    disguise.label,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (version != null) {
                    Text("Version $version", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    "No internet access. Everything stays encrypted on this device — " +
                        "no cloud, no accounts, no analytics.",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (namingShortcut) {
        CustomNameDialog(
            initialIcon = disguise,
            onAdd = { name, icon ->
                HomeShortcut.pin(context, name, icon)
                namingShortcut = false
            },
            onDismiss = { namingShortcut = false },
        )
    }

    if (pickingDisguise) {
        DisguiseDialog(
            current = disguise,
            onPick = {
                AppDisguise.apply(context, it)
                disguise = it
                pickingDisguise = false
            },
            onDismiss = { pickingDisguise = false },
        )
    }
}

/**
 * The real launcher art, drawn the way the launcher will draw it: the same
 * foreground vector on the same background colour, so what you pick is what
 * you get rather than a stand-in.
 */
@Composable
private fun DisguiseIcon(disguise: Disguise, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4.5f).dp))
            .background(colorResource(disguise.previewBackground)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(disguise.previewIcon),
            contentDescription = null,
            modifier = Modifier.size((size * 1.5f).dp),
        )
    }
}

/**
 * Type a name, pick an icon, and the launcher is asked to place it. This is the
 * only way to get an arbitrary name onto the home screen — a launcher entry's
 * own label has to come from the manifest, which is why the list above is a
 * fixed set and this isn't.
 */
@Composable
private fun CustomNameDialog(
    initialIcon: Disguise,
    onAdd: (String, Disguise) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(initialIcon) }
    val canPin = remember { HomeShortcut.supported(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name it yourself") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("e.g. Receipts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Icon",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Disguise.entries.forEach { option ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { icon = option }
                                .padding(2.dp),
                        ) {
                            DisguiseIcon(option, size = 36)
                            if (option == icon) {
                                Box(
                                    Modifier.matchParentSize().clip(RoundedCornerShape(9.dp))
                                        .background(Brass.copy(alpha = 0.28f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        null,
                                        tint = Ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    if (canPin) {
                        "This adds a home screen icon. Your app list still shows the name " +
                            "picked above — a launcher entry can't be renamed from inside the app."
                    } else {
                        "This launcher won't let apps place icons. You may need to allow " +
                            "shortcuts for this app in your launcher or system settings first."
                    },
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, icon) },
                enabled = name.isNotBlank() && canPin,
            ) {
                Text("Add to home screen", color = if (name.isNotBlank() && canPin) Brass else Faint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DisguiseDialog(current: Disguise, onPick: (Disguise) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home screen icon") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Disguise.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DisguiseIcon(option, size = 38)
                        Text(
                            option.label,
                            color = Ink,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (option == current) {
                            Icon(Icons.Filled.Check, null, tint = Brass, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Text(
                    "The icon can take a moment to change, and some launchers only " +
                        "pick it up after a restart.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Brass) } },
    )
}

private fun backupDate(millis: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
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

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
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
            .background(if (selected) Brass else Hairline)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = if (selected) BrassInk else Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
                .background(if (selected) Brass else Hairline),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Filled.Check, null, tint = BrassInk, modifier = Modifier.size(12.dp)) }
        Text(label, color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
