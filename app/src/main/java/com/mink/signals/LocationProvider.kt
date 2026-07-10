package com.mink.signals

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
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
 * Coordinate and movement data, read through [LocationManager]. A single fix
 * pins you to within a few meters, and altitude often narrows that to a floor.
 * Mink coarsens the coordinate it shows you and says so; the raw value never
 * leaves the device. Streams live while the detail screen is open.
 */
class LocationProvider(
    private val ctx: ProviderContext,
) : LiveSignalProvider {

    override val category: SignalCategory = SignalCategory.LOCATION

    override val updateIntervalMs: Long = 4000L

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }
        val manager = locationManager() ?: return unavailable()
        val last = lastKnown(manager)
        return buildSignals(manager, last)
    }

    override fun stream(): Flow<List<FingerprintSignal>> = callbackFlow {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            trySend(PermissionedFormat.gateClosed(category))
            awaitClose { }
            return@callbackFlow
        }
        val manager = locationManager()
        if (manager == null) {
            trySend(unavailable())
            awaitClose { }
            return@callbackFlow
        }

        // Immediate snapshot from the last cached fix, then stream updates.
        trySend(buildSignals(manager, lastKnown(manager)))

        // Implement the legacy callbacks too: before Android 11 they are abstract
        // at runtime, so a SAM lambda would risk an AbstractMethodError there.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(buildSignals(manager, location))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (name in providers) {
            runCatching {
                if (manager.isProviderEnabled(name)) {
                    manager.requestLocationUpdates(
                        name,
                        updateIntervalMs,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            }
        }

        awaitClose {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun locationManager(): LocationManager? = runCatching {
        ctx.appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull()

    private fun lastKnown(manager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (name in providers) {
            val fix = runCatching { manager.getLastKnownLocation(name) }.getOrNull()
            if (fix != null && (best == null || fix.time > best!!.time)) {
                best = fix
            }
        }
        return best
    }

    private fun buildSignals(manager: LocationManager, location: Location?): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val enabledProviders = runCatching {
            manager.getProviders(true).joinToString(", ").ifBlank { "none enabled" }
        }.getOrDefault("unknown")
        signals += FingerprintSignal.make(
            key = "providers",
            category = category,
            name = "Location providers",
            value = enabledProviders,
            rationale = "The location sources your phone has turned on right now.",
            displayHint = DisplayHint.TAGS,
        )

        if (location == null) {
            signals += FingerprintSignal.make(
                key = "fix",
                category = category,
                name = "Current fix",
                value = "No fix yet",
                rationale =
                    "No location is cached yet. Move around or open a map app and a reading " +
                        "usually appears within a few seconds.",
            )
            return signals
        }

        val lat = PermissionedFormat.coarseCoordinate(location.latitude)
        val lon = PermissionedFormat.coarseCoordinate(location.longitude)
        signals += FingerprintSignal.make(
            key = "coordinate",
            category = category,
            name = "Coordinate (coarsened)",
            value = "$lat, $lon",
            rationale =
                "Your position, rounded to about a kilometre before it is shown. The precise " +
                    "reading an app receives can pin you to within a few meters.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Latitude", lat),
                SignalEntry("Longitude", lon),
            ),
        )

        if (location.hasAccuracy()) {
            signals += FingerprintSignal.make(
                key = "accuracy",
                category = category,
                name = "Horizontal accuracy",
                value = "%.0f m".format(location.accuracy),
                rationale = "How tight the fix is. A small radius means an app can place you precisely.",
            )
        }

        if (location.hasAltitude()) {
            signals += FingerprintSignal.make(
                key = "altitude",
                category = category,
                name = "Altitude",
                value = "%.0f m".format(location.altitude),
                rationale =
                    "Your height above sea level. Inside a building this often narrows you down " +
                        "to a specific floor.",
            )
        }

        if (location.hasSpeed()) {
            signals += FingerprintSignal.make(
                key = "speed",
                category = category,
                name = "Speed",
                value = "%.1f m/s".format(location.speed),
                rationale = "How fast you are moving, which hints at whether you walk, cycle, or drive.",
            )
        }

        if (location.hasBearing()) {
            signals += FingerprintSignal.make(
                key = "bearing",
                category = category,
                name = "Bearing",
                value = "%.0f degrees".format(location.bearing),
                rationale = "The direction you are heading.",
            )
        }

        val providerName = location.provider ?: "unknown"
        signals += FingerprintSignal.make(
            key = "source",
            category = category,
            name = "Fix source",
            value = providerName,
            rationale = "Which sensor produced this reading.",
        )

        val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        } else {
            System.currentTimeMillis() - location.time
        }
        signals += FingerprintSignal.make(
            key = "age",
            category = category,
            name = "Reading age",
            value = PassiveFormat.formatDuration(ageMs.coerceAtLeast(0)),
            rationale = "How long ago this fix was taken.",
        )

        return signals
    }

    private fun unavailable(): List<FingerprintSignal> = listOf(
        FingerprintSignal.make(
            key = "unavailable",
            category = category,
            name = "Location service",
            value = "Unavailable",
            rationale = "This phone did not return a location service to read from.",
        ),
    )
}
