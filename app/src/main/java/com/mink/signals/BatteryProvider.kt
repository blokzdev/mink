package com.mink.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.LiveSignalProvider
import com.mink.core.provider.ProviderContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Charge, power, and thermal state. Battery level is a classic time domain
 * fingerprint: it changes slowly, so a site or app revisited within the same
 * minute sees the same reading and can re-identify you. Streams live from the
 * system battery broadcast. Nothing here needs a permission.
 */
class BatteryProvider(
    private val ctx: ProviderContext,
) : LiveSignalProvider {

    override val category: SignalCategory = SignalCategory.BATTERY

    override val updateIntervalMs: Long = 5000L

    override suspend fun collect(): List<FingerprintSignal> {
        val status = runCatching {
            ctx.appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return buildSignals(status)
    }

    override fun stream(): Flow<List<FingerprintSignal>> = callbackFlow {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(buildSignals(intent))
            }
        }
        // Emit an immediate snapshot, then stream on every battery change.
        val sticky = runCatching {
            ctx.appContext.registerReceiver(receiver, filter)
        }.getOrNull()
        trySend(buildSignals(sticky))

        awaitClose {
            runCatching { ctx.appContext.unregisterReceiver(receiver) }
        }
    }

    private fun buildSignals(status: Intent?): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val levelPct = batteryLevelPercent(status)
        val stateString = chargeState(status)
        signals += FingerprintSignal.make(
            key = "level",
            category = category,
            name = "Charge & state",
            value = "$levelPct / $stateString",
            rationale =
                "The charge level and whether you are plugged in. It moves slowly, so the value " +
                    "persists across short sessions and can tie them together.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Level", levelPct),
                SignalEntry("State", stateString),
            ),
        )

        signals += FingerprintSignal.make(
            key = "health",
            category = category,
            name = "Battery health",
            value = health(status),
            rationale = "The reported health of the battery cell.",
        )

        signals += FingerprintSignal.make(
            key = "plugged",
            category = category,
            name = "Power source",
            value = pluggedSource(status),
            rationale = "How the phone is powered right now, if it is charging.",
        )

        val technology = status?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        if (!technology.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "technology",
                category = category,
                name = "Cell technology",
                value = technology,
                rationale = "The battery chemistry, for example Li-ion.",
            )
        }

        val tempTenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tempTenths != null && tempTenths != Int.MIN_VALUE) {
            signals += FingerprintSignal.make(
                key = "temperature",
                category = category,
                name = "Temperature",
                value = "%.1f C".format(tempTenths / 10.0),
                rationale = "The battery temperature. It shifts with your workload and charging.",
            )
        }

        val voltage = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        if (voltage != null && voltage != Int.MIN_VALUE) {
            signals += FingerprintSignal.make(
                key = "voltage",
                category = category,
                name = "Voltage",
                value = "$voltage mV",
                rationale = "The current battery voltage in millivolts.",
            )
        }

        val power = runCatching {
            ctx.appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()

        if (power != null) {
            signals += FingerprintSignal.make(
                key = "powerSave",
                category = category,
                name = "Battery saver",
                value = if (runCatching { power.isPowerSaveMode }.getOrDefault(false)) "on" else "off",
                rationale = "Whether you have battery saver turned on.",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermal = runCatching { power.currentThermalStatus }.getOrNull()
                if (thermal != null) {
                    signals += FingerprintSignal.make(
                        key = "thermal",
                        category = category,
                        name = "Thermal status",
                        value = thermalName(thermal),
                        rationale =
                            "How hot the system currently runs. It can reflect a heavy workload " +
                                "or charging.",
                    )
                }
            }
        }

        return signals
    }

    private fun batteryLevelPercent(status: Intent?): String {
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return PassiveFormat.batteryPercent(level, scale)
    }

    private fun chargeState(status: Intent?): String {
        return when (status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
    }

    private fun health(status: Intent?): String {
        return when (status?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
            else -> "unknown"
        }
    }

    private fun pluggedSource(status: Intent?): String {
        return when (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "on battery"
            else -> "unknown"
        }
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
}
