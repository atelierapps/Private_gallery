package com.atelierapps.vault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atelierapps.vault.ui.grid.VaultGridScreen

/**
 * Home screen (spec §7, §8): the filter bar above the decrypting grid, driven
 * by [GridViewModel].
 */
@Composable
fun VaultHome(
    onOpen: (id: String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GridViewModel = viewModel(),
) {
    val filter by vm.filter.collectAsState()
    val sources by vm.sourceChips.collectAsState()
    val tags by vm.tagChips.collectAsState()
    val sort by vm.sort.collectAsState()
    val media by vm.media.collectAsState()

    Box(modifier.fillMaxSize().background(Color(0xFF0E1113))) {
        Column(Modifier.fillMaxSize()) {
            FilterBar(
                filter = filter,
                sources = sources,
                tags = tags,
                sort = sort,
                onSetType = vm::setType,
                onToggleSource = vm::toggleSource,
                onToggleTag = vm::toggleTag,
                onSetDate = vm::setDate,
                onSetSort = vm::setSort,
                onClearAll = vm::clearAll,
                modifier = Modifier.fillMaxWidth(),
            )
            VaultGridScreen(
                media = media,
                onOpen = onOpen,
                modifier = Modifier.weight(1f),
            )
        }
        FloatingActionButton(
            onClick = onImport,
            containerColor = Color(0xFFD8B463),
            contentColor = Color(0xFF1A1509),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Text("+", fontSize = 26.sp)
        }
    }
}
