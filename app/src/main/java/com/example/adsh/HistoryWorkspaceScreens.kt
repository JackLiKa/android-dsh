package com.example.adsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun WorkspaceScreen(
    workspaces: List<WorkspaceRef>,
    sessionCounts: Map<String, Int>,
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
        error?.let {
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (workspaces.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        stringResource(R.string.workspace_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workspaces, key = { it.id }) { workspace ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.workspace_session_count,
                                sessionCounts[workspace.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall)
                            Text(workspace.directory.absolutePath,
                                style = MaterialTheme.typography.labelSmall)
                            if (selectedWorkspaceId == workspace.id) {
                                Text(stringResource(R.string.profile_selected_suffix),
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
}
