package com.example.adsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.example.adsh.device.DeviceAuditLog
import com.example.adsh.device.DeviceDecision
import com.example.adsh.device.DeviceGovernor
import com.example.adsh.device.DevicePolicy
import com.example.adsh.device.DevicePolicyStore

@Composable
fun DevicePolicyCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { DevicePolicyStore(context) }
    val auditLog = remember { DeviceAuditLog(context) }
    var version by remember { mutableIntStateOf(0) }
    val auditVersion by DeviceGovernor.auditVersion.collectAsState()
    val policy = remember(version) { store.policy() }
    val audit = remember(version, auditVersion) { auditLog.recent(12) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { version++ }
    val permissionGranted = remember(version) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
    val notificationsOn = remember(version) {
        context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() != false
    }
    val notificationsBlocked = !permissionGranted || !notificationsOn

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.device_policy_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.device_policy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notificationsBlocked) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.device_policy_notifications_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    },
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.device_policy_notifications_grant)) }
                }
            }
            Text(
                stringResource(R.string.device_policy_preset_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { preset ->
                    FilterChip(
                        selected = policy.preset == preset,
                        onClick = {
                            store.setPreset(preset)
                            version++
                        },
                        label = { Text(DevicePolicy.presetLabel(preset)) },
                    )
                }
            }
            PolicyToggle(
                title = stringResource(R.string.device_policy_read_only),
                detail = stringResource(R.string.device_policy_read_only_hint),
                checked = policy.readOnly,
                onCheckedChange = {
                    store.setReadOnly(it)
                    version++
                },
            )
            PolicyToggle(
                title = stringResource(R.string.device_policy_sensitive),
                detail = stringResource(R.string.device_policy_sensitive_hint),
                checked = policy.blockSensitiveApps,
                onCheckedChange = {
                    store.setBlockSensitiveApps(it)
                    version++
                },
            )
            PolicyToggle(
                title = stringResource(R.string.device_policy_credential),
                detail = stringResource(R.string.device_policy_credential_hint),
                checked = policy.blockCredentialInput,
                onCheckedChange = {
                    store.setBlockCredentialInput(it)
                    version++
                },
            )
            Text(
                stringResource(R.string.device_policy_rules),
                style = MaterialTheme.typography.labelLarge,
            )
            if (policy.rules.isEmpty()) {
                Text(
                    stringResource(R.string.device_policy_rules_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                policy.rules.forEachIndexed { index, rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rule.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                store.removeRule(index)
                                version++
                            },
                        ) { Text(stringResource(R.string.device_policy_rule_delete)) }
                    }
                }
            }
            Text(
                stringResource(R.string.device_policy_audit),
                style = MaterialTheme.typography.labelLarge,
            )
            if (audit.isEmpty()) {
                Text(
                    stringResource(R.string.device_policy_audit_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                audit.forEach { entry ->
                    Text(
                        entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { version++ }) {
                    Text(stringResource(R.string.device_policy_audit_refresh))
                }
                TextButton(
                    onClick = {
                        DeviceGovernor.clearAudit()
                        version++
                    },
                ) { Text(stringResource(R.string.device_policy_audit_clear)) }
            }
            Text(
                stringResource(R.string.device_policy_applies_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PolicyToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Blocks the request until the user answers; the agent never sees this dialog
 * and cannot resolve it. A timeout is a denial.
 */
@Composable
fun DeviceApprovalDialog() {
    val approval by DeviceGovernor.pending.collectAsState()
    val current = approval ?: return
    var rememberRule by remember(current.id) { mutableStateOf(false) }
    val canRemember = DevicePolicy.canRemember(current.request)

    AlertDialog(
        onDismissRequest = {
            DeviceGovernor.resolve(current.id, DeviceDecision.DENY, rememberRule = false)
        },
        title = { Text(current.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.summary)
                Text(
                    stringResource(R.string.device_approval_timeout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canRemember) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberRule,
                            onCheckedChange = { rememberRule = it },
                        )
                        Text(
                            stringResource(R.string.device_approval_always),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    DeviceGovernor.resolve(
                        current.id,
                        DeviceDecision.ALLOW,
                        rememberRule = rememberRule,
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (rememberRule) R.string.device_approval_allow_remembered
                        else R.string.device_approval_allow_once,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    DeviceGovernor.resolve(
                        current.id,
                        DeviceDecision.DENY,
                        rememberRule = false,
                    )
                },
            ) { Text(stringResource(R.string.device_approval_deny)) }
        },
    )
}
