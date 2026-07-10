package com.mink.signals

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Reads the carrier and SIM surface that needs no prompt: the network operator,
 * the SIM operator, the phone type, the SIM and network country, roaming state,
 * and the modem count. Your carrier plus your country is a coarse but persistent
 * fingerprint, and it hints at where you are. Mink stays clear of the hardware
 * identifiers the OS blocks, such as the IMEI.
 */
// isDataEnabled needs a phone-state grant on some OS levels; the call is SDK
// guarded and wrapped in runCatching, which lint cannot trace here.
@SuppressLint("MissingPermission")
class TelephonyProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.TELEPHONY
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val telephony = runCatching {
            appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()

        if (telephony == null) {
            signals += FingerprintSignal.make(
                key = "status",
                category = category,
                name = "Telephony",
                value = "unavailable",
                rationale = "This device does not expose a telephony service, so there is no " +
                    "carrier or SIM to read.",
            )
            return signals
        }

        val phoneType = phoneTypeName(runCatching { telephony.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE))
        signals += FingerprintSignal.make(
            key = "phoneType",
            category = category,
            name = "Phone type",
            value = phoneType,
            rationale = "Whether this device has a GSM, CDMA, or no cellular radio. It splits " +
                "phones from tablets and marks the radio family.",
        )

        addString(
            signals, telephony, "networkOperator", "Network operator",
            "The carrier your phone is connected to right now. Your carrier is a coarse but " +
                "steady trait.",
        ) { it.networkOperatorName }

        addString(
            signals, telephony, "simOperator", "SIM operator",
            "The carrier that issued your SIM. It can differ from the network when you are " +
                "roaming.",
        ) { it.simOperatorName }

        val simCountry = runCatching { telephony.simCountryIso }.getOrNull()
        val networkCountry = runCatching { telephony.networkCountryIso }.getOrNull()
        if (!simCountry.isNullOrBlank() || !networkCountry.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "country",
                category = category,
                name = "Country",
                value = listOfNotNull(
                    simCountry?.takeIf { it.isNotBlank() }?.let { "SIM $it" },
                    networkCountry?.takeIf { it.isNotBlank() }?.let { "network $it" },
                ).joinToString(", ").ifBlank { "unknown" },
                rationale = "The country your SIM and network report. It places you on the map at " +
                    "country level with no location permission.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = buildList {
                    simCountry?.takeIf { it.isNotBlank() }?.let { add(SignalEntry("SIM", it.uppercase())) }
                    networkCountry?.takeIf { it.isNotBlank() }?.let { add(SignalEntry("Network", it.uppercase())) }
                },
            )
        }

        signals += FingerprintSignal.make(
            key = "simState",
            category = category,
            name = "SIM state",
            value = simStateName(runCatching { telephony.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)),
            rationale = "Whether a SIM is present, locked, or absent. Read from the telephony " +
                "service.",
        )

        val roaming = runCatching { telephony.isNetworkRoaming }.getOrNull()
        if (roaming != null) {
            signals += FingerprintSignal.make(
                key = "roaming",
                category = category,
                name = "Roaming",
                value = roaming.toString(),
                rationale = "Whether you are roaming off your home network. It hints that you are " +
                    "travelling.",
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val modems = runCatching { telephony.activeModemCount }.getOrNull()
            if (modems != null && modems > 0) {
                signals += FingerprintSignal.make(
                    key = "modems",
                    category = category,
                    name = "Active modems",
                    value = modems.toString(),
                    rationale = "How many cellular radios are active. A dual-SIM phone shows two, " +
                        "which narrows the model.",
                )
            }
        }

        val dataEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                @Suppress("DEPRECATION")
                telephony.isDataEnabled
            }.getOrNull()
        } else {
            null
        }
        if (dataEnabled != null) {
            signals += FingerprintSignal.make(
                key = "dataEnabled",
                category = category,
                name = "Mobile data enabled",
                value = dataEnabled.toString(),
                rationale = "Whether mobile data is switched on. A configuration flag, so Mink " +
                    "reveals it.",
            )
        }

        return signals
    }

    private inline fun addString(
        signals: MutableList<FingerprintSignal>,
        telephony: TelephonyManager,
        key: String,
        name: String,
        rationale: String,
        read: (TelephonyManager) -> String?,
    ) {
        val value = runCatching { read(telephony) }.getOrNull()
        if (!value.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = key,
                category = category,
                name = name,
                value = value,
                rationale = rationale,
            )
        }
    }

    companion object {
        /** Maps a TelephonyManager phone type to a readable name. Pure and testable. */
        fun phoneTypeName(type: Int): String = when (type) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            TelephonyManager.PHONE_TYPE_NONE -> "none"
            else -> "unknown"
        }

        /** Maps a TelephonyManager SIM state to a readable name. Pure and testable. */
        fun simStateName(state: Int): String = when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
            else -> "unknown"
        }
    }
}
