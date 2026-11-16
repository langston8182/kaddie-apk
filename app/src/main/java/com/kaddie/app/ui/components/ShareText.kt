package com.kaddie.app.ui.components

import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.usecase.ItemGrouping

object ShareText {
    /**
     * Plain-text version of the shopping list, for any messaging app. Only what is still to buy is shared
     * (bought products are done), optionally under aisle headings.
     */
    fun format(
        listName: String,
        items: List<ShoppingItem>,
        categories: Map<String, ProductCategory>,
        groupByCategory: Boolean,
    ): String {
        val toBuy = items.filter { !it.isPurchased }
        return buildString {
            append("🛒 ").append(listName)
            if (toBuy.isEmpty()) {
                append("\n\nTout est acheté !")
                return@buildString
            }
            if (groupByCategory) {
                ItemGrouping.byCategory(toBuy, categories).forEach { group ->
                    append("\n\n").append(ProductIcons.categoryIcon(group.category)).append(' ').append(group.category.label)
                    group.items.forEach { append('\n').append(line(it)) }
                }
            } else {
                append('\n')
                toBuy.forEach { append('\n').append(line(it)) }
            }
        }
    }

    private fun line(item: ShoppingItem): String {
        val remaining = if (item.isPartiallyPurchased) item.remainingQuantity else item.quantity
        val note = if (item.note.isNotBlank()) " (${item.note})" else ""
        return "• ${item.name} : ${formatQuantityWithUnit(remaining, item.unit)}$note"
    }
}
