package com.mink.data

import com.mink.core.model.SignalCategory
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import com.mink.signals.AccessibilityProvider
import com.mink.signals.AccountsProvider
import com.mink.signals.ActivityProvider
import com.mink.signals.AppInfoProvider
import com.mink.signals.AudioProvider
import com.mink.signals.BatteryProvider
import com.mink.signals.BluetoothProvider
import com.mink.signals.CalendarProvider
import com.mink.signals.CameraProvider
import com.mink.signals.ClipboardProvider
import com.mink.signals.ContactsProvider
import com.mink.signals.CpuProvider
import com.mink.signals.DeviceIdentityProvider
import com.mink.signals.DisplayProvider
import com.mink.signals.FontsProvider
import com.mink.signals.GpuProvider
import com.mink.signals.InstalledAppsProvider
import com.mink.signals.LocalNetworkProvider
import com.mink.signals.LocaleProvider
import com.mink.signals.LocationProvider
import com.mink.signals.MusicProvider
import com.mink.signals.NearbyWifiProvider
import com.mink.signals.NetworkProvider
import com.mink.signals.PhotosProvider
import com.mink.signals.SensorsProvider
import com.mink.signals.StorageProvider
import com.mink.signals.SystemInfoProvider
import com.mink.signals.SystemSettingsProvider
import com.mink.signals.TelephonyProvider
import com.mink.signals.VoicesProvider
import com.mink.signals.WebViewFingerprintProvider

/**
 * Builds the one true set of providers, keyed by category. The [SignalStore]
 * owns the resulting map; this factory exists so the provider list has a single
 * home and the store stays free of construction detail.
 *
 * Every [SignalCategory] must be covered exactly once. [validate] asserts that
 * invariant so a missing or duplicated provider fails loudly in debug builds.
 */
object ProviderRegistry {

    fun buildAll(ctx: ProviderContext): Map<SignalCategory, SignalProvider> {
        val all: List<SignalProvider> = listOf(
            // Passive
            DeviceIdentityProvider(ctx),
            SystemInfoProvider(ctx),
            AppInfoProvider(ctx),
            BatteryProvider(ctx),
            StorageProvider(ctx),
            DisplayProvider(ctx),
            AudioProvider(ctx),
            LocaleProvider(ctx),
            AccessibilityProvider(ctx),
            ClipboardProvider(ctx),
            SensorsProvider(ctx),
            NetworkProvider(ctx),
            LocalNetworkProvider(ctx),
            FontsProvider(ctx),
            VoicesProvider(ctx),
            CpuProvider(ctx),
            GpuProvider(ctx),
            TelephonyProvider(ctx),
            SystemSettingsProvider(ctx),
            // Permissioned
            LocationProvider(ctx),
            CameraProvider(ctx),
            BluetoothProvider(ctx),
            NearbyWifiProvider(ctx),
            ContactsProvider(ctx),
            CalendarProvider(ctx),
            PhotosProvider(ctx),
            MusicProvider(ctx),
            ActivityProvider(ctx),
            // Advanced
            InstalledAppsProvider(ctx),
            AccountsProvider(ctx),
            WebViewFingerprintProvider(ctx),
        )
        val registry = LinkedHashMap<SignalCategory, SignalProvider>()
        for (p in all) {
            check(registry[p.category] == null) { "Duplicate provider for ${p.category.id}" }
            registry[p.category] = p
        }
        validate(registry)
        return registry
    }

    private fun validate(registry: Map<SignalCategory, SignalProvider>) {
        val missing = SignalCategory.entries.filter { it !in registry }
        check(missing.isEmpty()) {
            "Missing providers for: ${missing.joinToString { it.id }}"
        }
    }
}
