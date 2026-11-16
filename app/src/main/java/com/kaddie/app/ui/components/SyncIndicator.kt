package com.kaddie.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaddie.app.domain.sync.SyncIssue
import com.kaddie.app.domain.sync.SyncUiState

/** Discreet cloud icon; tapping it retries the synchronization. The app never waits for it. */
@Composable
fun SyncIndicator(state: SyncUiState, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    val description = describe(state)
    IconButton(onClick = onSyncNow, modifier = modifier.semantics { contentDescription = description }) {
        when (state) {
            SyncUiState.Syncing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Icon(
                imageVector = iconFor(state),
                contentDescription = null,
                tint = when {
                    state is SyncUiState.Problem && state.issue != SyncIssue.AuthRequired && state.issue != SyncIssue.Offline ->
                        MaterialTheme.colorScheme.error
                    state == SyncUiState.Synced -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun iconFor(state: SyncUiState): ImageVector = when (state) {
    SyncUiState.Synced, SyncUiState.Syncing -> Icons.Filled.CloudDone
    is SyncUiState.Pending -> Icons.Filled.CloudQueue
    is SyncUiState.Problem -> when (state.issue) {
        SyncIssue.Offline -> Icons.Filled.CloudOff
        SyncIssue.AuthRequired -> Icons.Filled.CloudOff
        else -> Icons.Filled.SyncProblem
    }
}

private fun describe(state: SyncUiState): String = when (state) {
    SyncUiState.Synced -> "Synchronisé"
    SyncUiState.Syncing -> "Synchronisation en cours"
    is SyncUiState.Pending -> "${state.count} modification(s) en attente de synchronisation. Appuyez pour réessayer."
    is SyncUiState.Problem -> when (val issue = state.issue) {
        SyncIssue.Offline -> "Hors connexion : ${state.pending} modification(s) en attente. Appuyez pour réessayer."
        SyncIssue.ServerError -> "Serveur indisponible, nouvelle tentative automatique. Appuyez pour réessayer."
        SyncIssue.AuthRequired -> "Non connecté : vos listes ne sont pas sauvegardées. Appuyez pour vous connecter."
        is SyncIssue.Rejected -> "Certaines modifications ont été refusées par le serveur (${issue.message}). Vos données locales sont conservées."
        SyncIssue.None -> "Synchronisé"
    }
}
