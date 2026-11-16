package com.kaddie.app.domain.usecase

import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem

data class CategoryGroup(val category: ProductCategory, val items: List<ShoppingItem>)

object ItemGrouping {
    /**
     * Groups items by store aisle, in the fixed order of [ProductCategory]. Items keep their relative order
     * inside a group; products with an unknown category go to "Autres". Empty groups are omitted.
     */
    fun byCategory(items: List<ShoppingItem>, categories: Map<String, ProductCategory>): List<CategoryGroup> =
        items.groupBy { categories[it.productId] ?: ProductCategory.OTHER }
            .map { (category, group) -> CategoryGroup(category, group) }
            .sortedBy { it.category.ordinal }
}
