package com.mink.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.data.LoadState
import com.mink.data.PermissionController
import com.mink.data.PermissionStatus
import com.mink.data.SignalStore
import kotlinx.coroutines.flow.StateFlow

/**
 * A tiny [ViewModelProvider.Factory] that builds a ViewModel from a lambda, so
 * screens can inject the process-scoped [SignalStore] without a DI framework.
 */
class SimpleFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

/**
 * Backs the home screen: exposes the store's snapshot and load-state flows plus
 * the live permission statuses, and triggers a first collection pass.
 */
class HomeViewModel(
    private val store: SignalStore,
    permissions: PermissionController,
) : ViewModel() {

    val signals: StateFlow<Map<SignalCategory, List<com.mink.core.model.FingerprintSignal>>> =
        store.signals
    val loadStates: StateFlow<Map<SignalCategory, LoadState>> = store.loadStates
    val permissionStatuses: StateFlow<Map<PermissionKind, PermissionStatus>> = permissions.statuses

    fun refreshAll() = store.collectAll()

    fun count(category: SignalCategory): Int = store.count(category)
}

/**
 * Backs a single category detail screen. Starts one-shot or live collection on
 * open and stops any live stream on close.
 */
class CategoryViewModel(
    private val store: SignalStore,
    val category: SignalCategory,
    private val live: Boolean,
) : ViewModel() {

    val signals: StateFlow<Map<SignalCategory, List<com.mink.core.model.FingerprintSignal>>> =
        store.signals
    val loadStates: StateFlow<Map<SignalCategory, LoadState>> = store.loadStates

    fun start() {
        if (live) store.startLive(category) else store.collect(category)
    }

    fun refresh() = store.collect(category)

    fun stop() {
        if (live) store.stopLive(category)
    }

    override fun onCleared() {
        stop()
    }
}

/** Backs the summary screen. Runs a full sweep so the narrative has data. */
class SummaryViewModel(
    private val store: SignalStore,
) : ViewModel() {

    val signals: StateFlow<Map<SignalCategory, List<com.mink.core.model.FingerprintSignal>>> =
        store.signals

    fun sweep() = store.collectAll()
}
