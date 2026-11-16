package com.kaddie.app.domain.usecase

import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.ShoppingItem

object ItemRules {
    fun withPinned(item: ShoppingItem, pinned: Boolean, now: Long): ShoppingItem = item.copy(isPinned = pinned, updatedAt = now)

    /** Marking as purchased buys the whole requested quantity; unchecking resets what was bought. */
    fun withPurchased(item: ShoppingItem, purchased: Boolean, now: Long): ShoppingItem = item.copy(
        isPurchased = purchased,
        purchasedQuantity = if (purchased) item.quantity else 0.0,
        updatedAt = now,
    )

    /** Edits quantity/unit/note while keeping requested and purchased quantities consistent. */
    fun withEdit(
        item: ShoppingItem,
        quantity: Double,
        unit: MeasureUnit,
        note: String,
        now: Long,
        pinned: Boolean = item.isPinned,
    ): ShoppingItem {
        val newQuantity = QuantityMerger.round3(quantity)
        val purchased = when {
            unit != item.unit -> if (item.isPurchased) newQuantity else 0.0
            item.isPurchased -> item.quantity.coerceAtMost(newQuantity)
            else -> item.purchasedQuantity.coerceAtMost(newQuantity)
        }
        return item.copy(
            quantity = newQuantity,
            unit = unit,
            purchasedQuantity = purchased,
            isPurchased = QuantityMerger.isFullyPurchased(newQuantity, purchased),
            note = note.trim(),
            isPinned = pinned,
            updatedAt = now,
        )
    }
}
