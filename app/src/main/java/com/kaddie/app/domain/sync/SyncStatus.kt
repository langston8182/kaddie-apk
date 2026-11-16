package com.kaddie.app.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

sealed interface SyncIssue {
    data object None : SyncIssue
    data object Offline : SyncIssue
    data object ServerError : SyncIssue
    data object AuthRequired : SyncIssue

    /** Some writes were refused by the API (data stays untouched locally). */
    data class Rejected(val message: String) : SyncIssue
}

sealed interface SyncUiState {
    data object Synced : SyncUiState
    data object Syncing : SyncUiState
    data class Pending(val count: Int) : SyncUiState
    data class Problem(val issue: SyncIssue, val pending: Int) : SyncUiState
}

/** Observable synchronization state, shown as a discreet indicator. */
class SyncStatus {
    private val _syncing = MutableStateFlow(false)
    private val _issue = MutableStateFlow<SyncIssue>(SyncIssue.None)
    val issue: StateFlow<SyncIssue> = _issue.asStateFlow()

    fun setSyncing(value: Boolean) {
        _syncing.value = value
    }

    fun setIssue(issue: SyncIssue) {
        _issue.value = issue
    }

    fun uiState(pending: Flow<Int>): Flow<SyncUiState> =
        combine(_syncing, _issue, pending) { syncing, issue, count ->
            when {
                syncing -> SyncUiState.Syncing
                issue != SyncIssue.None -> SyncUiState.Problem(issue, count)
                count > 0 -> SyncUiState.Pending(count)
                else -> SyncUiState.Synced
            }
        }
}
