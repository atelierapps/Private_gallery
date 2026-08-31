package com.atelierapps.vault.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atelierapps.vault.media.TransferFailure

/**
 * What didn't make it, by name and reason.
 *
 * A run that reports "3 failed" and stops there leaves you unable to tell
 * whether three thumbnails or three irreplaceable videos are missing from the
 * backup you're about to trust, with no way to find out. Scrollable and capped,
 * because a run where everything failed shouldn't produce a wall of text — the
 * count already says that.
 */
@Composable
fun FailureList(failures: List<TransferFailure>, modifier: Modifier = Modifier) {
    if (failures.isEmpty()) return
    Column(
        modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
    ) {
        failures.take(30).forEach { failure ->
            Text(
                failure.name,
                color = Ink,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(failure.reason, color = Danger, style = MaterialTheme.typography.bodySmall)
        }
        if (failures.size > 30) {
            Text(
                "…and " + (failures.size - 30) + " more, all in the log",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
