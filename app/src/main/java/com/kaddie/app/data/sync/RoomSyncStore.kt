package com.kaddie.app.data.sync

import androidx.room.withTransaction
import com.kaddie.app.data.local.DictionaryProductEntity
import com.kaddie.app.data.local.KaddieDatabase
import com.kaddie.app.data.local.ShoppingItemEntity
import com.kaddie.app.data.local.ShoppingListEntity
import com.kaddie.app.data.remote.RemoteItem
import com.kaddie.app.data.remote.RemoteList
import com.kaddie.app.data.remote.RemoteProduct
import com.kaddie.app.data.repository.toDomain
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import com.kaddie.app.domain.sync.SyncOpType
import com.kaddie.app.domain.sync.SyncOperation
import com.kaddie.app.domain.sync.SyncStore
import com.kaddie.app.domain.text.TextNormalizer
import kotlinx.coroutines.flow.Flow

class RoomSyncStore(
    private val db: KaddieDatabase,
    private val outbox: OutboxRecorder,
    private val onDictionaryChanged: () -> Unit,
) : SyncStore {
    private val sync = db.syncDao()
    private val lists = db.shoppingListDao()
    private val products = db.productDao()

    override suspend fun nextOperation(): SyncOperation? = sync.first()?.let {
        SyncOperation(it.id, SyncOpType.valueOf(it.type), it.entityId, it.parentId, it.flag)
    }

    override suspend fun completeOperation(id: Long) = sync.delete(id)

    override suspend fun recordAttempt(id: Long) = sync.recordAttempt(id)

    override suspend fun seedOutbox() {
        db.withTransaction {
            products.getUserDefined().forEach { outbox.record(SyncOpType.PRODUCT_UPSERT, it.id) }
            lists.getAllLists().forEach { outbox.record(SyncOpType.LIST_UPSERT, it.id) }
            lists.getAllItems().forEach { outbox.record(SyncOpType.ITEM_CREATE, it.id, it.listId) }
        }
    }

    override suspend fun getList(id: String): ShoppingList? = lists.getList(id)?.toDomain()

    override suspend fun getItem(id: String): ShoppingItem? = lists.getItem(id)?.toDomain()

    override suspend fun getProduct(id: String): Product? = products.findById(id)?.toDomain()

    override suspend fun hasList(id: String): Boolean = lists.getList(id) != null

    override suspend fun rekeyItem(oldId: String, newId: String) {
        db.withTransaction {
            if (lists.getItem(newId) != null) {
                lists.deleteItem(oldId)
            } else {
                lists.rekeyItem(oldId, newId)
            }
            sync.rekey(oldId, newId)
        }
    }

    override suspend fun applyRemoteProducts(products: List<RemoteProduct>) {
        var changed = false
        db.withTransaction {
            val pending = sync.pendingEntityIds().toSet()
            for (remote in products) {
                if (remote.id in pending) continue
                val name = remote.name
                if (this.products.findById(remote.id) != null) {
                    this.products.updateUserProduct(remote.id, name, TextNormalizer.normalize(name), remote.category.code)
                    changed = true
                    continue
                }
                val key = TextNormalizer.key(name)
                // An equivalent product exists locally (same key): the local one wins.
                if (key.isEmpty() || this.products.findByKey(key) != null) continue
                this.products.insertProduct(
                    DictionaryProductEntity(
                        id = remote.id,
                        name = name,
                        normalized = TextNormalizer.normalize(name),
                        key = key,
                        category = remote.category.code,
                        isUserDefined = true,
                        usageCount = 0,
                        lastUsedAt = remote.updatedAt,
                    ),
                )
                changed = true
            }
        }
        if (changed) onDictionaryChanged()
    }

    override suspend fun applyRemoteLists(lists: List<RemoteList>) {
        db.withTransaction {
            val pending = sync.pendingEntityIds().toSet()
            for (remote in lists) {
                if (remote.id in pending) continue
                val local = this.lists.getList(remote.id)
                if (local == null) {
                    this.lists.insertListIfAbsent(ShoppingListEntity(remote.id, remote.name, remote.createdAt, remote.updatedAt))
                } else if (remote.updatedAt > local.updatedAt) {
                    this.lists.applyRemoteList(remote.id, remote.name, remote.updatedAt)
                }
            }
        }
    }

    override suspend fun applyRemoteItems(listId: String, items: List<RemoteItem>) {
        var dictionaryChanged = false
        db.withTransaction {
            val pending = sync.pendingEntityIds().toSet()
            if (listId in pending || lists.getList(listId) == null) return@withTransaction
            for (remote in items) {
                if (remote.id in pending) continue
                val local = lists.getItem(remote.id)
                if (local != null) {
                    if (remote.updatedAt > local.updatedAt) lists.updateItem(remote.toEntity(local.productId, listId))
                    continue
                }
                val productId = ensureProduct(remote)?.also { if (it.second) dictionaryChanged = true }?.first ?: continue
                // Product already in the list under another item id: the local item wins.
                if (lists.findItem(listId, productId) != null) continue
                lists.insertItemIfAbsent(remote.toEntity(productId, listId))
            }
        }
        if (dictionaryChanged) onDictionaryChanged()
    }

    /** Returns the local product id for a pulled item (and whether a product was created), or null if impossible. */
    private suspend fun ensureProduct(remote: RemoteItem): Pair<String, Boolean>? {
        products.findById(remote.productId)?.let { return it.id to false }
        val key = TextNormalizer.key(remote.name)
        if (key.isEmpty()) return null
        products.findByKey(key)?.let { return it.id to false }
        products.insertProduct(
            DictionaryProductEntity(
                id = remote.productId,
                name = remote.name,
                normalized = TextNormalizer.normalize(remote.name),
                key = key,
                category = "autres",
                isUserDefined = true,
                usageCount = 0,
                lastUsedAt = remote.updatedAt,
            ),
        )
        return remote.productId to true
    }

    override fun observePendingCount(): Flow<Int> = sync.observeCount()

    override fun observeLastOperationId(): Flow<Long?> = sync.observeLastId()
}

private fun RemoteItem.toEntity(productId: String, listId: String) = ShoppingItemEntity(
    id = id,
    listId = listId,
    productId = productId,
    name = name,
    quantity = quantity,
    unit = unit.name,
    purchasedQuantity = purchasedQuantity,
    isPurchased = isPurchased,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
