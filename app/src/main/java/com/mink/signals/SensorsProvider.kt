package com.mink.signals

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Enumerates every sensor the device exposes. The exact lineup of sensors,
 * with each part's vendor, resolution, and power draw, is a strong model
 * fingerprint on its own. No permission is needed to list them.
 */
class SensorsProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.SENSORS
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val manager = runCatching {
            appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }.getOrNull()

        if (manager == null) {
            signals += FingerprintSignal.make(
                key = "unavailable",
                category = category,
                name = "Sensor service",
                value = "unavailable",
                rationale = "The sensor service could not be read on this device.",
            )
            return signals
        }

        val sensors = runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())

        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Sensor count",
            value = sensors.size.toString(),
            rationale = "How many sensors this device carries. The total already narrows the set " +
                "of possible models.",
        )

        val vendors = sensors.map { it.vendor }.filter { it.isNotBlank() }.distinct()
        if (vendors.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "vendors",
                category = category,
                name = "Sensor vendors",
                value = vendors.joinToString(", "),
                rationale = "The vendors behind your sensors. The mix of suppliers is specific to " +
                    "your model.",
                displayHint = DisplayHint.TAGS,
                entries = vendors.map { SignalEntry(it, "") },
            )
        }

        for ((index, sensor) in sensors.withIndex()) {
            runCatching {
                val entries = buildList {
                    add(SignalEntry("Vendor", sensor.vendor))
                    add(SignalEntry("Type", typeName(sensor.type)))
                    add(SignalEntry("Version", sensor.version.toString()))
                    add(SignalEntry("Power", "${sensor.power} mA"))
                    add(SignalEntry("Resolution", sensor.resolution.toString()))
                    add(SignalEntry("Max range", sensor.maximumRange.toString()))
                    add(SignalEntry("Min delay", "${sensor.minDelay} us"))
                }
                signals += FingerprintSignal.make(
                    key = "sensor.$index.${sensor.type}",
                    category = category,
                    name = sensor.name,
                    value = "${sensor.vendor}, ${typeName(sensor.type)}",
                    rationale = "One sensor's identity. Its vendor, resolution, and power draw are " +
                        "fixed by the hardware and rarely change.",
                    displayHint = DisplayHint.KEY_VALUE,
                    entries = entries,
                )
            }
        }

        return signals
    }

    companion object {
        /** Maps a [Sensor] type constant to a readable label. Pure and testable. */
        fun typeName(type: Int): String = when (type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic field"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_LIGHT -> "Light"
            Sensor.TYPE_PRESSURE -> "Pressure"
            Sensor.TYPE_PROXIMITY -> "Proximity"
            Sensor.TYPE_GRAVITY -> "Gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative humidity"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temperature"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope (uncalibrated)"
            Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
            Sensor.TYPE_STEP_DETECTOR -> "Step detector"
            Sensor.TYPE_STEP_COUNTER -> "Step counter"
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation vector"
            Sensor.TYPE_HEART_RATE -> "Heart rate"
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic field (uncalibrated)"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "Accelerometer (uncalibrated)"
            else -> "Type $type"
        }
    }
}
