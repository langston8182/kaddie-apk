package com.kaddie.app.domain.usecase

import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.matching.Resolution
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.repository.TransactionRunner
import java.util.UUID

enum class UnitConflictResolution {
    /** Drop the existing quantity and use the newly entered one. */
    REPLACE,

    /** Keep the existing quantity and ignore the newly entered one. */
    KEEP_EXISTING,
}

data class AddItemRequest(
    val listId: String,
    val name: String,
    val quantity: Double = 1.0,
    val unit: MeasureUnit = MeasureUnit.PIECE,
    val note: String = "",
    /** Set when the user picked a suggestion or confirmed a correction. */
    val productId: String? = null,
    /** Set when the user declined the proposed corrections and wants a brand new product. */
    val createAsNew: Boolean = false,
    val unitConflict: UnitConflictResolution? = null,
)

sealed interface AddItemResult {
    data class Added(val item: ShoppingItem) : AddItemResult
    data class Merged(val item: ShoppingItem) : AddItemResult
    data class Unchanged(val item: ShoppingItem) : AddItemResult
    data class NeedsCorrection(val request: AddItemRequest, val candidates: List<Product>) : AddItemResult
    data class NeedsUnitDecision(val request: AddItemRequest, val existing: ShoppingItem) : AddItemResult
    data class Invalid(val reason: String) : AddItemResult
}

class AddShoppingListItemUseCase(
    private val repository: ShoppingListRepository,
    private val matching: ProductMatchingService,
    private val transactions: TransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(request: AddItemRequest): AddItemResult {
        if (request.name.isBlank() && request.productId == null) {
            return AddItemResult.Invalid("Nom de produit vide")
        }
        if (!(request.quantity > 0.0) || request.quantity.isInfinite()) {
            return AddItemResult.Invalid("Quantité invalide")
        }
        return try {
            transactions.run { execute(request) }
        } catch (e: Exception) {
            // The dictionary cache may reference a product from the rolled-back transaction.
            matching.invalidate()
            throw e
        }
    }

    private suspend fun execute(request: AddItemRequest): AddItemResult {
        val product = when {
            request.productId != null -> matching.getById(request.productId)
                ?: matching.createProduct(request.name)
            request.createAsNew -> matching.createProduct(request.name)
            else -> when (val resolution = matching.resolve(request.name)) {
                is Resolution.Exact -> resolution.product
                is Resolution.Uncertain ->
                    return AddItemResult.NeedsCorrection(request, resolution.candidates)
                Resolution.NotFound -> matching.createProduct(request.name)
            }
        }

        val now = clock()
        val existing = repository.findItemByProduct(request.listId, product.id)
        val result = if (existing == null) {
            val item = ShoppingItem(
                id = UUID.randomUUID().toString(),
                listId = request.listId,
                productId = product.id,
                name = product.name,
                quantity = QuantityMerger.round3(request.quantity),
                unit = request.unit,
                purchasedQuantity = 0.0,
                isPurchased = false,
                note = request.note.trim(),
                createdAt = now,
                updatedAt = now,
            )
            repository.insertItem(item)
            AddItemResult.Added(item)
        } else {
            merge(existing, request, now) ?: return AddItemResult.NeedsUnitDecision(request, existing)
        }

        matching.recordUsage(product.id)
        repository.touchList(request.listId)
        return result
    }

    private suspend fun merge(existing: ShoppingItem, request: AddItemRequest, now: Long): AddItemResult? {
        val incoming = Quantity(request.quantity, request.unit)
        val sum = QuantityMerger.add(Quantity(existing.quantity, existing.unit), incoming)
        val note = existing.note.ifBlank { request.note.trim() }

        val updated = when {
            sum != null -> {
                val purchased = QuantityMerger.convert(existing.purchasedQuantity, existing.unit, sum.unit)
                existing.copy(
                    quantity = sum.amount,
                    unit = sum.unit,
                    purchasedQuantity = purchased,
                    // Adding to an already bought product puts it back on the "to buy" side.
                    isPurchased = QuantityMerger.isFullyPurchased(sum.amount, purchased),
                    note = note,
                    updatedAt = now,
                )
            }
            request.unitConflict == UnitConflictResolution.REPLACE -> existing.copy(
                quantity = QuantityMerger.round3(incoming.amount),
                unit = incoming.unit,
                purchasedQuantity = 0.0,
                isPurchased = false,
                note = note,
                updatedAt = now,
            )
            request.unitConflict == UnitConflictResolution.KEEP_EXISTING -> return AddItemResult.Unchanged(existing)
            else -> return null
        }
        repository.updateItem(updated)
        return AddItemResult.Merged(updated)
    }
}
