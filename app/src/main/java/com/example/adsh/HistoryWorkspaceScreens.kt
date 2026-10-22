package com.example.adsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    summaries: List<SessionSummary>,
    workspaces: List<WorkspaceRef>,
    selectedWorkspaceId: String,
    enabled: Boolean,
    onOpen: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var currentOnly by remember { mutableStateOf(false) }
    val names = workspaces.associate { it.id to it.name }
    val visible = summaries.filter { summary ->
        (!currentOnly || summary.workspaceId == selectedWorkspaceId) &&
            (summary.preview.contains(query, ignoreCase = true) ||
                summary.id.contains(query, ignoreCase = true) ||
                names[summary.workspaceId].orEmpty().contains(query, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.history), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_history)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !currentOnly, onClick = { currentOnly = false },
                label = { Text(stringResource(R.string.all_workspaces)) })
            FilterChip(selected = currentOnly, onClick = { currentOnly = true },
                label = { Text(stringResource(R.string.current_workspace_only)) })
        }
        if (visible.isEmpty()) {
            Text(stringResource(if (summaries.isEmpty()) R.string.no_history else R.string.no_search_results))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { summary ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(summary.preview.ifEmpty { summary.id.take(8) },
                                style = MaterialTheme.typography.titleSmall)
                            Text("${names[summary.workspaceId] ?: summary.workspaceId} · " +
                                stringResource(R.string.history_count, summary.eventCount) + " · " +
                                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                                    .format(Date(summary.lastEventTime)),
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onOpen(summary.id) }, enabled = enabled) {
                                Text(stringResource(R.string.chat))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspaceScreen(
    workspaces: List<WorkspaceRef>,
    summaries: List<SessionSummary>,
    selectedWorkspaceId: String,
    error: String?,
    enabled: Boolean,
    onCreate: (String) -> Unit,
    onSelect: (WorkspaceRef) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(selectedWorkspaceId) { newName = "" }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.workspaces), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.workspace_private), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = newName, onValueChange = { newName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.workspace_name)) }, singleLine = true)
        Button(onClick = { onCreate(newName) },
            enabled = enabled && newName.isNotBlank()) {
            Text(stringResource(R.string.create_workspace))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(workspaces, key = { it.id }) { workspace ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.workspace_session_count,
                            summaries.count { it.workspaceId == workspace.id }),
                            style = MaterialTheme.typography.bodySmall)
                        Text(workspace.directory.absolutePath,
                            style = MaterialTheme.typography.labelSmall)
                        if (selectedWorkspaceId == workspace.id) {
                            Text(stringResource(R.string.selected),
                                color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { onSelect(workspace) }, enabled = enabled) {
                                Text(stringResource(R.string.select_workspace))
                            }
                        }
                    }
                }
            }
        }
    }
}
