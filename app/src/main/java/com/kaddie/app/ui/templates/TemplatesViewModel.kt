package com.kaddie.app.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.matching.Resolution
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.repository.TemplateRepository
import com.kaddie.app.ui.lists.TemplatesState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class TemplatesViewModel(
    private val repository: TemplateRepository,
    private val matching: ProductMatchingService,
) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    private val _templates = MutableStateFlow<TemplatesState>(TemplatesState.Idle)
    val templates: StateFlow<TemplatesState> = _templates.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _templates.value = TemplatesState.Loading
            _templates.value = when (val result = repository.list()) {
                is RemoteResult.Success -> TemplatesState.Loaded(result.value)
                is RemoteResult.Failure -> TemplatesState.Failed(describe(result.error))
            }
        }
    }

    fun delete(templateId: String) {
        viewModelScope.launch {
            when (val result = repository.delete(templateId)) {
                is RemoteResult.Success -> refresh()
                is RemoteResult.Failure -> messages.trySend(describe(result.error))
            }
        }
    }

    fun save(template: ListTemplate, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = repository.update(template)) {
                is RemoteResult.Success -> {
                    refresh()
                    onSaved()
                }
                is RemoteResult.Failure -> messages.trySend(describe(result.error))
            }
        }
    }

    suspend fun suggest(query: String): List<Product> =
        if (query.isBlank()) emptyList() else matching.suggest(query, limit = 6).map { it.product }

    /** The existing product matching [name] exactly, or a new user product. */
    suspend fun productFor(name: String): Product = when (val resolution = matching.resolve(name)) {
        is Resolution.Exact -> resolution.product
        else -> matching.createProduct(name.trim())
    }

    private fun describe(error: RemoteError): String = when (error) {
        is RemoteError.Network -> "Connexion requise pour les modèles"
        RemoteError.Unauthorized -> "Connectez-vous pour utiliser les modèles"
        is RemoteError.Conflict -> error.message
        is RemoteError.Invalid -> error.message
        else -> "Les modèles sont momentanément indisponibles"
    }
}
