package com.kaddie.app.data.remote

import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.usecase.UnitConflictResolution

data class RemoteList(val id: String, val name: String, val createdAt: Long, val updatedAt: Long)

data class RemoteItem(
    val id: String,
    val listId: String,
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: MeasureUnit,
    val purchasedQuantity: Double,
    val isPurchased: Boolean,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** null when the server does not know the field (older API): the local value is kept. */
    val isPinned: Boolean? = null,
)

data class RemoteProduct(
    val id: String,
    val name: String,
    val category: ProductCategory,
    val createdAt: Long,
    val updatedAt: Long,
)

data class RemoteTemplateItem(
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: MeasureUnit,
    val note: String,
)

data class RemoteTemplate(val id: String, val name: String, val items: List<RemoteTemplateItem>)

data class RemoteAddItem(
    val id: String,
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: MeasureUnit,
    val note: String,
    val unitConflict: UnitConflictResolution? = null,
)

enum class AddResult { ADDED, MERGED, UNCHANGED }

data class AddItemOutcome(val result: AddResult, val item: RemoteItem)

sealed interface RemoteError {
    /** No connection, timeout, DNS... : retry later. */
    data class Network(val cause: Throwable?) : RemoteError
    data object Unauthorized : RemoteError
    data object NotFound : RemoteError

    /** 409 UNIT_CONFLICT on item add: resend with an explicit `unitConflict` resolution. */
    data class UnitConflict(val existing: RemoteItem?) : RemoteError

    /** Other 409 (e.g. an equivalent personal product already exists). */
    data class Conflict(val message: String) : RemoteError

    /** 400 and other rejected requests: retrying will not help. */
    data class Invalid(val message: String) : RemoteError
    data class Server(val status: Int) : RemoteError
}

sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class Failure(val error: RemoteError) : RemoteResult<Nothing>
}

/** Seam to the Kaddie REST API. Local operations never depend on it: Room stays the source of truth. */
interface ShoppingRemoteDataSource {
    suspend fun fetchLists(updatedSince: Long?): RemoteResult<List<RemoteList>>
    suspend fun upsertList(id: String, name: String): RemoteResult<RemoteList>
    suspend fun deleteList(id: String): RemoteResult<Unit>
    suspend fun clearItems(listId: String, purchasedOnly: Boolean): RemoteResult<Unit>
    suspend fun uncheckAll(listId: String): RemoteResult<Unit>

    suspend fun fetchItems(listId: String, updatedSince: Long?): RemoteResult<List<RemoteItem>>
    suspend fun addItem(listId: String, item: RemoteAddItem): RemoteResult<AddItemOutcome>
    suspend fun updateItem(listId: String, itemId: String, quantity: Double, unit: MeasureUnit, note: String, isPinned: Boolean): RemoteResult<RemoteItem>
    suspend fun setPurchased(listId: String, itemId: String, purchased: Boolean): RemoteResult<RemoteItem>
    suspend fun deleteItem(listId: String, itemId: String): RemoteResult<Unit>

    suspend fun fetchProducts(updatedSince: Long?): RemoteResult<List<RemoteProduct>>
    suspend fun upsertProduct(id: String, name: String, category: ProductCategory): RemoteResult<RemoteProduct>

    suspend fun fetchTemplates(): RemoteResult<List<RemoteTemplate>>
    suspend fun upsertTemplate(id: String, name: String, items: List<RemoteTemplateItem>): RemoteResult<RemoteTemplate>
    suspend fun deleteTemplate(id: String): RemoteResult<Unit>
}
