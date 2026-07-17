package com.mink.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resumes the opt-in DNS-flow monitor after a reboot or an app update, but only
 * if the user had left it on and the VPN consent still stands. This is
 * best-effort: `VpnService.prepare` returns null only while consent persists, and
 * some Android versions / OEMs restrict starting a foreground service from a boot
 * broadcast. For a guaranteed resume the user can mark Mink as an always-on VPN
 * in system settings (with the trade-off that the system then also restarts the
 * monitor after an in-app Stop — the off switch moves to that same settings
 * screen); this receiver just restores the common case.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Per-app attribution (and thus the whole feature) needs API 29+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Consent must definitively still stand. Fail closed: prepare() can throw
        // (e.g. VPN lockdown mode), and an indeterminate consent check must read
        // as "no" — never start the monitor on a maybe, never prompt at boot.
        val consentStands = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        if (!consentStands) return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (DnsFlowStore(app).loadEnabled()) {
                    runCatching { FlowMonitorService.start(app) }
                        .onFailure { Log.w(TAG, "boot resume failed: $it") }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "DnsFlowBoot"
    }
}
