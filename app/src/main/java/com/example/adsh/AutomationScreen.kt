package com.example.adsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val presets = listOf(
    "设明早 7 点闹钟",
    "给联系人预填短信",
    "看看屏幕上的内容",
    "总结最近的通知",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen(
    routines: List<AutomationRoutine>,
    engineReady: Boolean,
    onRun: (AutomationRoutine) -> Unit,
    onAdd: (String, String) -> Unit,
    onDelete: (AutomationRoutine) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.automation_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.automation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!engineReady) {
            Text(
                stringResource(R.string.automation_engine_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.automation_new),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.automation_name_hint)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.automation_name)) },
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.automation_prompt_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    label = { Text(stringResource(R.string.automation_prompt)) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { prompt = preset },
                            label = { Text(preset) },
                        )
                    }
                }
                Button(
                    onClick = {
                        onAdd(title, prompt)
                        title = ""
                        prompt = ""
                    },
                    enabled = prompt.isNotBlank(),
                ) {
                    Text(stringResource(R.string.automation_add))
                }
            }
        }

        Text(
            stringResource(R.string.automation_saved),
            style = MaterialTheme.typography.titleMedium,
        )
        if (routines.isEmpty()) {
            Text(
                stringResource(R.string.automation_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        routines.forEach { routine ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                routine.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                routine.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onDelete(routine) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.automation_delete),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onRun(routine) },
                            enabled = engineReady,
                        ) {
                            Text(stringResource(R.string.automation_run))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.automation_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
