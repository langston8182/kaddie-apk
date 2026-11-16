package com.kaddie.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaddie.app.domain.model.ShoppingListSummary
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.repository.TemplateRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TemplatesState {
    data object Idle : TemplatesState
    data object Loading : TemplatesState
    data class Loaded(val templates: List<ListTemplate>) : TemplatesState
    data class Failed(val message: String) : TemplatesState
}

class ListsViewModel(
    private val repository: ShoppingListRepository,
    private val templateRepository: TemplateRepository,
) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    /** null while the first load is in progress. */
    val summaries: StateFlow<List<ShoppingListSummary>?> = repository.observeSummaries()
        .catch { messages.trySend("Impossible de charger les listes") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _templates = MutableStateFlow<TemplatesState>(TemplatesState.Idle)
    val templates: StateFlow<TemplatesState> = _templates.asStateFlow()

    /** Templates live on the server: they are fetched each time the "new list" dialog opens. */
    fun refreshTemplates() {
        viewModelScope.launch {
            _templates.value = TemplatesState.Loading
            _templates.value = when (val result = templateRepository.list()) {
                is RemoteResult.Success -> TemplatesState.Loaded(result.value)
                is RemoteResult.Failure -> TemplatesState.Failed(describe(result.error))
            }
        }
    }

    fun createFromTemplate(template: ListTemplate, name: String, onCreated: (String) -> Unit) =
        launchSafely("Création impossible") { onCreated(templateRepository.createListFromTemplate(template, name)) }

    fun saveAsTemplate(listId: String, name: String) {
        viewModelScope.launch {
            when (val result = templateRepository.saveFromList(listId, name)) {
                is RemoteResult.Success -> messages.trySend("Modèle « ${result.value.name} » enregistré")
                is RemoteResult.Failure -> messages.trySend(describe(result.error))
            }
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            when (val result = templateRepository.delete(templateId)) {
                is RemoteResult.Success -> refreshTemplates()
                is RemoteResult.Failure -> messages.trySend(describe(result.error))
            }
        }
    }

    private fun describe(error: RemoteError): String = when (error) {
        is RemoteError.Network -> "Connexion requise pour les modèles"
        RemoteError.Unauthorized -> "Connectez-vous pour utiliser les modèles"
        is RemoteError.Conflict -> error.message
        is RemoteError.Invalid -> error.message
        else -> "Les modèles sont momentanément indisponibles"
    }

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
