package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atelierapps.vault.ui.grid.VaultGridScreen

/**
 * Home screen (spec §7, §8): the filter bar above the decrypting grid, driven
 * by [GridViewModel].
 */
@Composable
fun VaultHome(
    onOpen: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: GridViewModel = viewModel(),
) {
    val filter by vm.filter.collectAsState()
    val sources by vm.sourceChips.collectAsState()
    val tags by vm.tagChips.collectAsState()
    val media by vm.media.collectAsState()

    Column(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        FilterBar(
            filter = filter,
            sources = sources,
            tags = tags,
            onToggleSource = vm::toggleSource,
            onToggleTag = vm::toggleTag,
            onSetDate = vm::setDate,
            onClearAll = vm::clearAll,
            modifier = Modifier.fillMaxWidth(),
        )
        VaultGridScreen(
            media = media,
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
        )
    }
}
