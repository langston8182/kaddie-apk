package com.kaddie.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.matching.Suggestion
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.usecase.AddItemRequest
import com.kaddie.app.domain.usecase.AddItemResult
import com.kaddie.app.domain.usecase.AddShoppingListItemUseCase
import com.kaddie.app.domain.usecase.ItemRules
import com.kaddie.app.domain.usecase.UnitConflictResolution
import com.kaddie.app.ui.components.formatQuantityWithUnit
import com.kaddie.app.ui.components.parseQuantity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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

    val suggestions: StateFlow<List<Suggestion>> = query
        .mapLatest { matching.suggest(it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        query.value = value.take(80)
    }

    fun onQuantityChange(value: String) {
        quantityText.value = value.filter { it.isDigit() || it == ',' || it == '.' }.take(8)
    }

    fun onUnitChange(value: MeasureUnit) {
        unit.value = value
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
        prompt.value = null
        execute(if (chosen != null) request.copy(productId = chosen.id) else request.copy(createAsNew = true))
    }

    fun resolveUnitConflict(request: AddItemRequest, resolution: UnitConflictResolution) {
        prompt.value = null
        execute(request.copy(unitConflict = resolution))
    }

    fun dismissPrompt() {
        prompt.value = null
    }

    private fun execute(request: AddItemRequest) {
        viewModelScope.launch {
            try {
                when (val result = addItem(request)) {
                    is AddItemResult.Added -> {
                        resetForm()
                        messages.trySend(UiMessage("${result.item.name} ajouté"))
                    }
                    is AddItemResult.Merged -> {
                        resetForm()
                        messages.trySend(
                            UiMessage("${result.item.name} : ${formatQuantityWithUnit(result.item.quantity, result.item.unit)}"),
                        )
                    }
                    is AddItemResult.Unchanged -> {
                        resetForm()
                        messages.trySend(UiMessage("${result.item.name} : quantité inchangée"))
                    }
                    is AddItemResult.NeedsCorrection ->
                        prompt.value = Prompt.Correction(result.request, result.candidates)
                    is AddItemResult.NeedsUnitDecision ->
                        prompt.value = Prompt.UnitConflict(result.request, result.existing)
                    is AddItemResult.Invalid -> messages.trySend(UiMessage(result.reason))
                }
            } catch (e: Exception) {
                messages.trySend(UiMessage("Impossible d'ajouter le produit"))
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

    fun edit(item: ShoppingItem, quantity: Double, unit: MeasureUnit, note: String) =
        launchSafely("Modification impossible") {
            repository.updateItem(ItemRules.withEdit(item, quantity, unit, note, clock()))
        }

    fun delete(item: ShoppingItem) = launchSafely("Suppression impossible") {
        repository.deleteItem(item.id)
        messages.trySend(
            UiMessage("${item.name} supprimé", "Annuler") {
                launchSafely("Restauration impossible") { repository.insertItem(item); repository.touchList(listId) }
            },
        )
    }

    fun clearAll() = launchSafely("Impossible de vider la liste") {
        repository.clearItems(listId)
        messages.trySend(UiMessage("Liste vidée"))
    }

    fun clearPurchased() = launchSafely("Opération impossible") {
        repository.clearPurchasedItems(listId)
        messages.trySend(UiMessage("Produits achetés retirés"))
    }

    fun uncheckAll() = launchSafely("Opération impossible") {
        repository.uncheckAllItems(listId)
        messages.trySend(UiMessage("Tous les produits sont à acheter"))
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
}
