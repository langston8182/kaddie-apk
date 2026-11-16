package com.kaddie.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaddie.app.domain.model.ShoppingListSummary
import com.kaddie.app.domain.repository.ShoppingListRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ListsViewModel(private val repository: ShoppingListRepository) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    /** null while the first load is in progress. */
    val summaries: StateFlow<List<ShoppingListSummary>?> = repository.observeSummaries()
        .catch { messages.trySend("Impossible de charger les listes") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createList(name: String, onCreated: (String) -> Unit) = launchSafely("Création impossible") {
        onCreated(repository.createList(name).id)
    }

    fun rename(listId: String, name: String) = launchSafely("Renommage impossible") {
        repository.renameList(listId, name)
    }

    fun delete(listId: String) = launchSafely("Suppression impossible") {
        repository.deleteList(listId)
        messages.trySend("Liste supprimée")
    }

    fun duplicate(listId: String) = launchSafely("Duplication impossible") {
        repository.duplicateList(listId)
        messages.trySend("Liste dupliquée")
    }

    private fun launchSafely(error: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                messages.trySend(error)
            }
        }
    }
}
