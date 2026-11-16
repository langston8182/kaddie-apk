package com.kaddie.app.domain.repository

import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductWithAliases
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import com.kaddie.app.domain.model.ShoppingListSummary
import kotlinx.coroutines.flow.Flow

interface ProductDictionaryRepository {
    suspend fun loadAll(): List<ProductWithAliases>
    suspend fun findByKey(key: String): Product?
    suspend fun insert(product: Product, aliasKeys: List<String>)
    suspend fun incrementUsage(productId: String)
}

interface ShoppingListRepository {
    fun observeSummaries(): Flow<List<ShoppingListSummary>>
    fun observeList(listId: String): Flow<ShoppingList?>
    fun observeItems(listId: String): Flow<List<ShoppingItem>>

    suspend fun createList(name: String): ShoppingList
    suspend fun renameList(listId: String, name: String)
    suspend fun deleteList(listId: String)
    /** Copies a list and its items (all reset to "to buy"); returns the new list id. */
    suspend fun duplicateList(listId: String): String?

    suspend fun findItemByProduct(listId: String, productId: String): ShoppingItem?
    suspend fun insertItem(item: ShoppingItem)
    suspend fun updateItem(item: ShoppingItem)
    suspend fun deleteItem(itemId: String)
    suspend fun getItem(itemId: String): ShoppingItem?
    suspend fun touchList(listId: String)

    suspend fun clearItems(listId: String)
    suspend fun clearPurchasedItems(listId: String)
    /** Puts every item back to "to buy", keeping the list for the next shopping trip. */
    suspend fun uncheckAllItems(listId: String)
}

interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}
