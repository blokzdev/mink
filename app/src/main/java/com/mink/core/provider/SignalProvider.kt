package com.mink.core.provider

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import kotlinx.coroutines.flow.Flow

/**
 * Every fingerprinting surface is exposed through this single contract.
 *
 * Providers receive an Android [android.content.Context] at construction (via
 * [ProviderContext]) so they can read system services. [collect] does a
 * one-shot read and must be safe to call off the main thread; providers that
 * touch main-thread-only APIs marshal internally.
 */
interface SignalProvider {
    val category: SignalCategory

    /** The permission gate this provider sits behind, if any. */
    val permission: PermissionKind? get() = category.permission

    /** Take a one-shot snapshot of every signal this surface exposes. */
    suspend fun collect(): List<FingerprintSignal>
}

/**
 * A provider whose values change over time and can be streamed live (battery,
 * sensors, network). The store subscribes to [stream] when a detail screen is
 * open and cancels it when the screen closes.
 */
interface LiveSignalProvider : SignalProvider {
    /** Nominal refresh cadence in milliseconds; advisory for the UI. */
    val updateIntervalMs: Long

    /** Emits a fresh snapshot whenever the underlying values change. */
    fun stream(): Flow<List<FingerprintSignal>>
}
