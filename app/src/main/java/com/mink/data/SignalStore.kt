package com.mink.data

import android.content.Context
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.Sensitivity
import com.mink.core.provider.LiveSignalProvider
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Load state for a single category's collection. */
sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Loaded : LoadState
    data class Denied(val reason: String) : LoadState
}

/**
 * Central state holder for the whole app. Owns every provider, the current
 * signal snapshot per category, and each category's load state. This is the
 * Android analogue of Loupe's CategoryStore, exposed as observable
 * [StateFlow]s so Compose and the guardian can both react.
 *
 * The store is process-scoped (created by [com.mink.MinkApplication]) and
 * survives configuration changes; ViewModels read from it.
 */
class SignalStore(
    context: Context,
    private val scope: CoroutineScope,
    val permissions: PermissionController,
) {
    private val providers: Map<SignalCategory, SignalProvider> =
        ProviderRegistry.buildAll(ProviderContext(context.applicationContext))

    private val _signals = MutableStateFlow<Map<SignalCategory, List<FingerprintSignal>>>(emptyMap())
    val signals: StateFlow<Map<SignalCategory, List<FingerprintSignal>>> = _signals.asStateFlow()

    private val _loadStates = MutableStateFlow<Map<SignalCategory, LoadState>>(
        SignalCategory.entries.associateWith { LoadState.Idle },
    )
    val loadStates: StateFlow<Map<SignalCategory, LoadState>> = _loadStates.asStateFlow()

    private val liveJobs = mutableMapOf<SignalCategory, Job>()

    // ---- Accessors ----

    fun signals(category: SignalCategory): List<FingerprintSignal> =
        _signals.value[category].orEmpty()

    fun loadState(category: SignalCategory): LoadState =
        _loadStates.value[category] ?: LoadState.Idle

    fun count(category: SignalCategory): Int = signals(category).size

    fun categories(sensitivity: Sensitivity): List<SignalCategory> =
        SignalCategory.entries.filter { it.sensitivity == sensitivity }

    // ---- Collection ----

    /** One-shot collection for a category, updating its snapshot and state. */
    fun collect(category: SignalCategory) {
        val provider = providers[category] ?: return
        provider.permission?.let { kind ->
            if (!permissions.isGranted(kind)) {
                setState(category, LoadState.Denied(kind.title))
                return
            }
        }
        setState(category, LoadState.Loading)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { provider.collect() }
            result.onSuccess { list ->
                putSignals(category, list)
                setState(category, LoadState.Loaded)
            }.onFailure {
                setState(category, LoadState.Denied(it.message ?: "Unavailable"))
            }
        }
    }

    /** Collect every category (used for the summary and guardian sweep). */
    fun collectAll() {
        SignalCategory.entries.forEach(::collect)
    }

    /** Begin streaming a live category; no-op for one-shot providers. */
    fun startLive(category: SignalCategory) {
        val provider = providers[category] as? LiveSignalProvider ?: run {
            collect(category); return
        }
        provider.permission?.let { kind ->
            if (!permissions.isGranted(kind)) {
                setState(category, LoadState.Denied(kind.title)); return
            }
        }
        if (liveJobs[category]?.isActive == true) return
        setState(category, LoadState.Loading)
        liveJobs[category] = scope.launch(Dispatchers.IO) {
            provider.stream().collect { list ->
                putSignals(category, list)
                setState(category, LoadState.Loaded)
            }
        }
    }

    fun stopLive(category: SignalCategory) {
        liveJobs.remove(category)?.cancel()
    }

    // ---- Mutation helpers ----

    private fun putSignals(category: SignalCategory, list: List<FingerprintSignal>) {
        _signals.value = _signals.value.toMutableMap().apply { this[category] = list }
    }

    private fun setState(category: SignalCategory, state: LoadState) {
        _loadStates.value = _loadStates.value.toMutableMap().apply { this[category] = state }
    }
}
