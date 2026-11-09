package com.example.adsh.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DeviceApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESOLVE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val decision = runCatching {
            DeviceDecision.valueOf(intent.getStringExtra(EXTRA_DECISION).orEmpty())
        }.getOrNull() ?: return
        DeviceGovernor.attach(context)
        DeviceGovernor.resolve(id, decision, rememberRule = false)
    }

    companion object {
        const val ACTION_RESOLVE = "com.example.adsh.device.APPROVAL_RESULT"
        const val EXTRA_ID = "approval_id"
        const val EXTRA_DECISION = "approval_decision"
    }
}
