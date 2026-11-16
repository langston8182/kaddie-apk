package com.kaddie.app.domain.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface SyncScheduler {
    /** Runs a sync as soon as the network allows, with backoff on failure. */
    fun requestSync()

    /** Periodic background pull of remote changes. */
    fun schedulePeriodic()
}

/** Triggers background syncs when local writes are queued, and exposes the indicator state. */
@OptIn(FlowPreview::class)
class SyncManager(
    store: SyncStore,
    private val scheduler: SyncScheduler,
    status: SyncStatus,
    scope: CoroutineScope,
) {
    val uiState: StateFlow<SyncUiState> =
        status.uiState(store.observePendingCount()).stateIn(scope, SharingStarted.Eagerly, SyncUiState.Synced)

    init {
        scope.launch {
            var last = 0L
            // A new operation always has a higher id; deletions by the sync itself must not retrigger it.
            store.observeLastOperationId().debounce(300).collect { id ->
                if (id != null && id > last) {
                    last = id
                    scheduler.requestSync()
                }
            }
        }
    }

    fun start() {
        scheduler.requestSync()
        scheduler.schedulePeriodic()
    }

    fun syncNow() = scheduler.requestSync()
}
