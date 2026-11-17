package com.kaddie.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.matching.Suggestion
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.data.repository.mergeTemplateItems
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.repository.TemplateRepository
import com.kaddie.app.ui.lists.TemplatesState
import com.kaddie.app.domain.repository.DeleteProductResult
import com.kaddie.app.domain.usecase.AddItemRequest
import com.kaddie.app.domain.usecase.AddItemResult
import com.kaddie.app.domain.usecase.AddShoppingListItemUseCase
import com.kaddie.app.domain.usecase.ItemRules
import com.kaddie.app.domain.usecase.UnitConflictResolution
import com.kaddie.app.domain.voice.VoiceParser
import com.kaddie.app.ui.components.formatQuantity
import com.kaddie.app.ui.components.parseQuantity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

sealed interface Prompt {
    data class Correction(val request: AddItemRequest, val candidates: List<Product>) : Prompt
    data class UnitConflict(val request: AddItemRequest, val existing: ShoppingItem) : Prompt
}

/** Distinguishes "still loading" from "list does not exist". */
sealed interface ListHeader {
    data object Loading : ListHeader
    data object Missing : ListHeader
    data class Loaded(val name: String) : ListHeader
}

class ListDetailViewModel(
    private val listId: String,
    private val repository: ShoppingListRepository,
    private val matching: ProductMatchingService,
    private val addItem: AddShoppingListItemUseCase,
    private val templateRepository: TemplateRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    val header: StateFlow<ListHeader> = repository.observeList(listId)
        .map { if (it == null) ListHeader.Missing else ListHeader.Loaded(it.name) }
        .catch { emit(ListHeader.Missing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListHeader.Loading)

    val items: StateFlow<List<ShoppingItem>?> = repository.observeItems(listId)
        .catch { messages.trySend(UiMessage("Impossible de charger les produits")); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<Map<String, ProductCategory>> = items
        .mapLatest { matching.categories() }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val query = MutableStateFlow("")
    val quantityText = MutableStateFlow("1")
    val unit = MutableStateFlow(MeasureUnit.PIECE)
    val prompt = MutableStateFlow<Prompt?>(null)

    /** Questions raised while adding several products by voice, asked one after the other. */
    private val queuedPrompts = ArrayDeque<Prompt>()

    private fun showPrompt(value: Prompt) {
        if (prompt.value == null) prompt.value = value else queuedPrompts.addLast(value)
    }

    private fun nextPrompt() {
        prompt.value = queuedPrompts.removeFirstOrNull()
    }

    val suggestions: StateFlow<List<Suggestion>> = query
        .mapLatest { matching.suggest(it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Most used products not already in the list, offered while the search field is empty. */
    val habitual: StateFlow<List<Product>> = combine(query, items) { q, list -> q.isBlank() to list }
        .mapLatest { (blank, list) ->
            if (blank && list != null) matching.frequent(HABITUAL_LIMIT, list.map { it.productId }.toSet()) else emptyList()
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query.value = value.take(80)
    }

    fun onQuantityChange(value: String) {
        quantityText.value = value.filter { it.isDigit() || it == ',' || it == '.' }.take(8)
    }

    /** +/- buttons: one piece at a time, half a kilo/litre, or 50 g/mL, never below one step. */
    fun stepQuantity(direction: Int) {
        val step = when (unit.value) {
            MeasureUnit.KG, MeasureUnit.L -> 0.5
            MeasureUnit.G, MeasureUnit.ML -> 50.0
            else -> 1.0
        }
        val current = parseQuantity(quantityText.value) ?: 1.0
        quantityText.value = formatQuantity((current + direction * step).coerceAtLeast(step))
    }

    fun onUnitChange(value: MeasureUnit) {
        unit.value = value
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            val message: String? = try {
                when (matching.deleteProduct(product.id)) {
                    DeleteProductResult.DELETED -> {
                        // Refresh the suggestions now that the product is gone.
                        val q = query.value
                        query.value = ""
                        query.value = q
                        null
                    }
                    DeleteProductResult.IN_USE -> "« ${product.name} » est encore dans une liste : retirez-le d'abord"
                    DeleteProductResult.NOT_ALLOWED -> "« ${product.name} » fait partie du dictionnaire de base"
                }
            } catch (e: Exception) {
                "Suppression impossible"
            }
            if (message != null) messages.trySend(UiMessage(message))
        }
    }

    /** Adds the typed name, or the tapped suggestion when [suggestion] is given. */
    fun submit(suggestion: Product? = null) {
        val name = suggestion?.name ?: query.value
        if (name.isBlank()) return
        val quantity = parseQuantity(quantityText.value)
        if (quantity == null) {
            messages.trySend(UiMessage("Quantité invalide"))
            return
        }
        execute(
            AddItemRequest(
                listId = listId,
                name = name,
                quantity = quantity,
                unit = unit.value,
                productId = suggestion?.id,
            ),
        )
    }

    fun resolveCorrection(request: AddItemRequest, chosen: Product?) {
        nextPrompt()
        execute(if (chosen != null) request.copy(productId = chosen.id) else request.copy(createAsNew = true))
    }

    fun resolveUnitConflict(request: AddItemRequest, resolution: UnitConflictResolution) {
        nextPrompt()
        execute(request.copy(unitConflict = resolution))
    }

    fun dismissPrompt() {
        nextPrompt()
    }

    private fun execute(request: AddItemRequest) {
        viewModelScope.launch {
            try {
                when (val result = addItem(request)) {
                    is AddItemResult.Added, is AddItemResult.Merged, is AddItemResult.Unchanged -> resetForm()
                    is AddItemResult.NeedsCorrection ->
                        showPrompt(Prompt.Correction(result.request, result.candidates))
                    is AddItemResult.NeedsUnitDecision ->
                        showPrompt(Prompt.UnitConflict(result.request, result.existing))
                    is AddItemResult.Invalid -> messages.trySend(UiMessage(result.reason))
                }
            } catch (e: Exception) {
                messages.trySend(UiMessage("Impossible d'ajouter le produit"))
            }
        }
    }

    /** Adds what was dictated ("deux kilos de tomates et du lait"); questions are queued, not lost. */
    fun addFromVoice(text: String) {
        val spoken = VoiceParser.parse(text)
        if (spoken.isEmpty()) {
            messages.trySend(UiMessage("Je n'ai pas compris. Essayez : « deux kilos de tomates »"))
            return
        }
        viewModelScope.launch {
            for (item in spoken) {
                try {
                    when (val result = addItem(AddItemRequest(listId, item.name, item.quantity, item.unit))) {
                        is AddItemResult.NeedsCorrection -> showPrompt(Prompt.Correction(result.request, result.candidates))
                        is AddItemResult.NeedsUnitDecision -> showPrompt(Prompt.UnitConflict(result.request, result.existing))
                        is AddItemResult.Added, is AddItemResult.Merged, is AddItemResult.Unchanged, is AddItemResult.Invalid -> Unit
                    }
                } catch (e: Exception) {
                    messages.trySend(UiMessage("Impossible d'ajouter ${item.name}"))
                }
            }
        }
    }

    private val _templates = MutableStateFlow<TemplatesState>(TemplatesState.Idle)
    val templates: StateFlow<TemplatesState> = _templates.asStateFlow()

    /** Templates live on the server: they are fetched each time the import dialog opens. */
    fun refreshTemplates() {
        viewModelScope.launch {
            _templates.value = TemplatesState.Loading
            _templates.value = when (val result = templateRepository.list()) {
                is RemoteResult.Success -> TemplatesState.Loaded(result.value)
                is RemoteResult.Failure -> TemplatesState.Failed(
                    when (result.error) {
                        is RemoteError.Network -> "Connexion requise pour les modèles"
                        RemoteError.Unauthorized -> "Connectez-vous pour utiliser les modèles"
                        else -> "Les modèles sont momentanément indisponibles"
                    },
                )
            }
        }
    }

    /** Adds the products of the chosen templates to this list, merging with what is already there. */
    fun importTemplates(selected: List<ListTemplate>) {
        val templateItems = mergeTemplateItems(selected)
        if (templateItems.isEmpty()) {
            messages.trySend(UiMessage("Ces modèles sont vides"))
            return
        }
        viewModelScope.launch {
            for (t in templateItems) {
                try {
                    when (val result = addItem(AddItemRequest(listId, t.name, t.quantity, t.unit, t.note, productId = t.productId))) {
                        is AddItemResult.NeedsCorrection -> showPrompt(Prompt.Correction(result.request, result.candidates))
                        is AddItemResult.NeedsUnitDecision -> showPrompt(Prompt.UnitConflict(result.request, result.existing))
                        is AddItemResult.Added, is AddItemResult.Merged, is AddItemResult.Unchanged, is AddItemResult.Invalid -> Unit
                    }
                } catch (e: Exception) {
                    messages.trySend(UiMessage("Impossible d'ajouter ${t.name}"))
                }
            }
        }
    }

    private fun resetForm() {
        query.value = ""
        quantityText.value = "1"
        unit.value = MeasureUnit.PIECE
    }

    fun setPurchased(item: ShoppingItem, purchased: Boolean) = launchSafely("Modification impossible") {
        repository.updateItem(ItemRules.withPurchased(item, purchased, clock()))
    }

    fun edit(item: ShoppingItem, quantity: Double, unit: MeasureUnit, note: String, pinned: Boolean) =
        launchSafely("Modification impossible") {
            repository.updateItem(ItemRules.withEdit(item, quantity, unit, note, clock(), pinned))
        }

    fun togglePinned(item: ShoppingItem) = launchSafely("Modification impossible") {
        repository.updateItem(ItemRules.withPinned(item, !item.isPinned, clock()))
    }

    fun delete(item: ShoppingItem) = launchSafely("Suppression impossible") {
        repository.deleteItem(item.id)
    }

    fun clearAll() = launchSafely("Impossible de vider la liste") {
        repository.clearItems(listId)
    }

    fun clearPurchased() = launchSafely("Opération impossible") {
        repository.clearPurchasedItems(listId)
    }

    fun uncheckAll() = launchSafely("Opération impossible") {
        repository.uncheckAllItems(listId)
    }

    private fun launchSafely(error: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                messages.trySend(UiMessage(error))
            }
        }
    }

    private companion object {
        const val HABITUAL_LIMIT = 8
    }
}
