package com.kaddie.app.domain.sync

import com.kaddie.app.data.remote.RemoteItem
import com.kaddie.app.data.remote.RemoteList
import com.kaddie.app.data.remote.RemoteProduct
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import kotlinx.coroutines.flow.Flow

enum class SyncOpType {
    LIST_UPSERT,
    LIST_DELETE,
    LIST_CLEAR,
    LIST_UNCHECK_ALL,
    ITEM_CREATE,
    ITEM_STATE,
    ITEM_PURCHASED,
    ITEM_DELETE,
    PRODUCT_UPSERT,
}

/**
 * A pending local write waiting to be sent. Operations only carry what changed: the data itself is
 * read from Room when the operation is sent, which keeps them idempotent and always up to date.
 */
data class SyncOperation(
    val id: Long,
    val type: SyncOpType,
    val entityId: String,
    /** List id for item operations. */
    val parentId: String? = null,
    /** `purchasedOnly` for [SyncOpType.LIST_CLEAR]. */
    val flag: Boolean = false,
)

/** Local persistence needed by [SyncEngine]; implemented on top of Room, faked in tests. */
interface SyncStore {
    suspend fun nextOperation(): SyncOperation?
    suspend fun completeOperation(id: Long)
    suspend fun recordAttempt(id: Long)

    /** Enqueues everything that already exists locally (first sync, data created before the API). */
    suspend fun seedOutbox()

    suspend fun getList(id: String): ShoppingList?
    suspend fun getItem(id: String): ShoppingItem?
    suspend fun getProduct(id: String): Product?
    suspend fun hasList(id: String): Boolean

    /** The server already holds this product under another id: adopt the server's id locally. */
    suspend fun rekeyItem(oldId: String, newId: String)

    /** Remote data is applied without overwriting entities that still have pending local operations. */
    suspend fun applyRemoteProducts(products: List<RemoteProduct>)
    suspend fun applyRemoteLists(lists: List<RemoteList>)
    suspend fun applyRemoteItems(listId: String, items: List<RemoteItem>)

    fun observePendingCount(): Flow<Int>
    fun observeLastOperationId(): Flow<Long?>
}

interface SyncPreferences {
    /** Account the local sync state (seeding, cursors) belongs to. */
    var userId: String?
    var seeded: Boolean
    var listsCursor: Long?
    var productsCursor: Long?
}
