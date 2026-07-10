package com.mink.signals

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.LiveSignalProvider
import com.mink.core.provider.ProviderContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Step counts and the motion sensors behind them, read through [SensorManager].
 * The hardware step counter reports steps since the last reboot, a slow moving
 * value that streams a picture of when you move and rest. Streams live while the
 * detail screen is open.
 */
class ActivityProvider(
    private val ctx: ProviderContext,
) : LiveSignalProvider {

    override val category: SignalCategory = SignalCategory.ACTIVITY

    override val updateIntervalMs: Long = 3000L

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }
        val manager = sensorManager() ?: return unavailable()
        val steps = readStepsOnce(manager)
        return buildSignals(manager, steps)
    }

    override fun stream(): Flow<List<FingerprintSignal>> = callbackFlow {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            trySend(PermissionedFormat.gateClosed(category))
            awaitClose { }
            return@callbackFlow
        }
        val manager = sensorManager()
        if (manager == null) {
            trySend(unavailable())
            awaitClose { }
            return@callbackFlow
        }

        // Snapshot the inventory immediately; the step value fills in on the
        // first sensor event.
        trySend(buildSignals(manager, null))

        val counter = runCatching {
            manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        }.getOrNull()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val value = event?.values?.firstOrNull()?.toLong()
                trySend(buildSignals(manager, value))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (counter != null) {
            runCatching {
                manager.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        awaitClose {
            runCatching { manager.unregisterListener(listener) }
        }
    }

    private fun sensorManager(): SensorManager? = runCatching {
        ctx.appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }.getOrNull()

    private suspend fun readStepsOnce(manager: SensorManager): Long? {
        val counter = runCatching {
            manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        }.getOrNull() ?: return null

        return withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val value = event?.values?.firstOrNull()?.toLong()
                        if (cont.isActive) cont.resume(value)
                        runCatching { manager.unregisterListener(this) }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                val registered = runCatching {
                    manager.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL)
                }.getOrDefault(false)
                if (!registered && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation {
                    runCatching { manager.unregisterListener(listener) }
                }
            }
        }
    }

    private fun buildSignals(manager: SensorManager, steps: Long?): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val hasCounter = hasSensor(manager, Sensor.TYPE_STEP_COUNTER)
        val hasDetector = hasSensor(manager, Sensor.TYPE_STEP_DETECTOR)
        val hasSignificant = hasSensor(manager, Sensor.TYPE_SIGNIFICANT_MOTION)

        signals += FingerprintSignal.make(
            key = "steps",
            category = category,
            name = "Steps since boot",
            value = when {
                !hasCounter -> "No step counter"
                steps == null -> "Waiting for a reading"
                else -> steps.toString()
            },
            rationale =
                "The hardware step count since the last reboot. It rises as you walk, so repeated " +
                    "readings trace when you are moving and when you are still.",
        )

        val entries = listOf(
            SignalEntry("Step counter", if (hasCounter) "present" else "absent"),
            SignalEntry("Step detector", if (hasDetector) "present" else "absent"),
            SignalEntry("Significant motion", if (hasSignificant) "present" else "absent"),
        )
        signals += FingerprintSignal.make(
            key = "motionSensors",
            category = category,
            name = "Motion sensors",
            value = entries.joinToString("  |  ") { "${it.label}: ${it.value}" },
            rationale =
                "Which motion sensors this phone carries. The exact lineup is fixed by the " +
                    "hardware and helps identify the model.",
            displayHint = DisplayHint.KEY_VALUE,
            entries = entries,
        )

        return signals
    }

    private fun hasSensor(manager: SensorManager, type: Int): Boolean =
        runCatching { manager.getDefaultSensor(type) != null }.getOrDefault(false)

    private fun unavailable(): List<FingerprintSignal> = listOf(
        FingerprintSignal.make(
            key = "unavailable",
            category = category,
            name = "Sensor service",
            value = "Unavailable",
            rationale = "This phone did not return a sensor service to read from.",
        ),
    )

    private companion object {
        const val ONE_SHOT_TIMEOUT_MS = 1500L
    }
}
