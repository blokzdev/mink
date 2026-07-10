package com.mink.signals

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The Bluetooth adapter and the devices you have paired with it. Bonded gear,
 * the speakers, watches, and cars you own, forms a stable set that is often
 * unique to you. The hardware address is redacted by the OS; Mink shows only
 * the metadata it is allowed to read.
 */
// Every read below is gated by the BLUETOOTH_CONNECT grant at runtime (via the
// store and the checks here) and wrapped in runCatching; lint cannot trace that
// indirection through ProviderContext, so the check is suppressed at the class.
@SuppressLint("MissingPermission")
class BluetoothProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.BLUETOOTH

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val adapter = adapter() ?: return listOf(
            FingerprintSignal.make(
                key = "unsupported",
                category = category,
                name = "Bluetooth",
                value = "Not supported",
                rationale = "This phone did not return a Bluetooth adapter to read from.",
            ),
        )

        val signals = mutableListOf<FingerprintSignal>()

        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        signals += FingerprintSignal.make(
            key = "state",
            category = category,
            name = "Adapter state",
            value = if (enabled) "on" else "off",
            rationale = "Whether the Bluetooth radio is currently on.",
        )

        val name = runCatching { adapter.name }.getOrNull()
        if (!name.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "name",
                category = category,
                name = "Adapter name",
                value = name,
                rationale =
                    "The broadcast name of your phone. It often carries your own name or a label " +
                        "you chose, which nearby devices can see.",
            )
        }

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        signals += FingerprintSignal.make(
            key = "bondedCount",
            category = category,
            name = "Paired devices",
            value = bonded.size.toString(),
            rationale =
                "How many devices you have paired. The set of gear you own is stable and often " +
                    "unique to you.",
        )

        if (bonded.isNotEmpty()) {
            val entries = bonded.map { device ->
                val label = deviceName(device).ifBlank { "unnamed device" }
                SignalEntry(label, deviceType(device))
            }.sortedBy { it.label.lowercase() }
            signals += FingerprintSignal.make(
                key = "bonded",
                category = category,
                name = "Paired device types",
                value = entries.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "The names and kinds of gear paired to this phone. Device names frequently " +
                        "include their owners' names.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }

        return signals
    }

    private fun adapter(): BluetoothAdapter? = runCatching {
        val manager = ctx.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }.getOrNull()

    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty()

    private fun deviceType(device: BluetoothDevice): String {
        val transport = when (runCatching { device.type }.getOrNull()) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "low energy"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        }
        val major = majorClass(device)
        return if (major != null) "$major, $transport" else transport
    }

    private fun majorClass(device: BluetoothDevice): String? {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return null
        return when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio or video"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.HEALTH -> "health"
            BluetoothClass.Device.Major.PERIPHERAL -> "peripheral"
            BluetoothClass.Device.Major.IMAGING -> "imaging"
            BluetoothClass.Device.Major.TOY -> "toy"
            BluetoothClass.Device.Major.NETWORKING -> "networking"
            else -> "other"
        }
    }
}
