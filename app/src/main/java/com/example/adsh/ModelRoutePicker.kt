package com.example.adsh

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

@Composable
fun ModelRoutePicker(
    selected: ModelRoute,
    routes: List<ModelRoute>,
    enabled: Boolean,
    onSelect: (ModelRoute) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && routes.isNotEmpty()) {
            Text(stringResource(R.string.choose_model, selected.model))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            routes.forEach { route ->
                DropdownMenuItem(
                    text = { Text("${route.provider} / ${route.model}") },
                    onClick = {
                        expanded = false
                        onSelect(route)
                    },
                )
            }
        }
    }
}
