package com.kaddie.app.data.repository

import androidx.room.withTransaction
import com.kaddie.app.data.local.DictionaryProductEntity
import com.kaddie.app.data.local.KaddieDatabase
import com.kaddie.app.data.local.ListSummaryRow
import com.kaddie.app.data.local.ProductAliasEntity
import com.kaddie.app.data.local.ShoppingItemEntity
import com.kaddie.app.data.local.ShoppingListEntity
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ProductWithAliases
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import com.kaddie.app.domain.model.ShoppingListSummary
import com.kaddie.app.domain.repository.ProductDictionaryRepository
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.repository.TransactionRunner
import com.kaddie.app.domain.sync.SyncOpType
import com.kaddie.app.data.sync.OutboxRecorder
import com.kaddie.app.domain.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomTransactionRunner(private val db: KaddieDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

class RoomProductDictionaryRepository(
    private val db: KaddieDatabase,
    private val outbox: OutboxRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProductDictionaryRepository {
    private val dao = db.productDao()

    override suspend fun loadAll(): List<ProductWithAliases> {
        val aliases = dao.getAllAliases().groupBy({ it.productId }, { it.aliasKey })
        return dao.getAll().map { ProductWithAliases(it.toDomain(), aliases[it.id].orEmpty()) }
    }

    override suspend fun findByKey(key: String): Product? = dao.findByKey(key)?.toDomain()

    override suspend fun insert(product: Product, aliasKeys: List<String>) {
        db.withTransaction {
            dao.insertProduct(product.toEntity(clock()))
            dao.insertAliases(aliasKeys.map { ProductAliasEntity(product.id, it) })
            if (product.isUserDefined) outbox.record(SyncOpType.PRODUCT_UPSERT, product.id)
        }
    }

    override suspend fun incrementUsage(productId: String) = dao.incrementUsage(productId, clock())
}

class RoomShoppingListRepository(
    private val db: KaddieDatabase,
    private val outbox: OutboxRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) : ShoppingListRepository {
    private val dao = db.shoppingListDao()

    override fun observeSummaries(): Flow<List<ShoppingListSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeList(listId: String): Flow<ShoppingList?> =
        dao.observeList(listId).map { it?.toDomain() }

    override fun observeItems(listId: String): Flow<List<ShoppingItem>> =
        dao.observeItems(listId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createList(name: String): ShoppingList = db.withTransaction {
        val now = clock()
        val entity = ShoppingListEntity(UUID.randomUUID().toString(), name.trim(), now, now)
        dao.insertList(entity)
        outbox.record(SyncOpType.LIST_UPSERT, entity.id)
        entity.toDomain()
    }

    override suspend fun renameList(listId: String, name: String) = db.withTransaction {
        dao.renameList(listId, name.trim(), clock())
        outbox.record(SyncOpType.LIST_UPSERT, listId)
    }

    override suspend fun deleteList(listId: String) = db.withTransaction {
        dao.deleteList(listId)
        // The remote delete removes the items too: pending writes for this list are now pointless.
        outbox.dropItemOperations(listId)
        outbox.dropListOperations(listId)
        outbox.record(SyncOpType.LIST_DELETE, listId)
    }

    override suspend fun duplicateList(listId: String): String? = db.withTransaction {
        val source = dao.getList(listId) ?: return@withTransaction null
        val now = clock()
        val copy = ShoppingListEntity(UUID.randomUUID().toString(), "${source.name} (copie)", now, now)
        dao.insertList(copy)
        val copies = dao.getItems(listId).map {
            it.copy(
                id = UUID.randomUUID().toString(),
                listId = copy.id,
                purchasedQuantity = 0.0,
                isPurchased = false,
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.insertItems(copies)
        outbox.record(SyncOpType.LIST_UPSERT, copy.id)
        copies.forEach { outbox.record(SyncOpType.ITEM_CREATE, it.id, copy.id) }
        copy.id
    }

    override suspend fun findItemByProduct(listId: String, productId: String): ShoppingItem? =
        dao.findItem(listId, productId)?.toDomain()

    override suspend fun insertItem(item: ShoppingItem) = db.withTransaction {
        dao.insertItem(item.toEntity())
        outbox.record(SyncOpType.ITEM_CREATE, item.id, item.listId)
    }

    override suspend fun updateItem(item: ShoppingItem) = db.withTransaction {
        val old = dao.getItem(item.id)
        dao.updateItem(item.toEntity())
        dao.touchList(item.listId, clock())
        val purchaseOnly = old != null && old.quantity == item.quantity && old.unit == item.unit.name &&
            old.note == item.note && old.isPinned == item.isPinned && (item.purchasedQuantity == 0.0 || item.isPurchased)
        outbox.record(if (purchaseOnly) SyncOpType.ITEM_PURCHASED else SyncOpType.ITEM_STATE, item.id, item.listId)
    }

    override suspend fun deleteItem(itemId: String) = db.withTransaction {
        val item = dao.getItem(itemId) ?: return@withTransaction
        dao.deleteItem(itemId)
        dao.touchList(item.listId, clock())
        outbox.record(SyncOpType.ITEM_DELETE, itemId, item.listId)
    }

    override suspend fun getItem(itemId: String): ShoppingItem? = dao.getItem(itemId)?.toDomain()

    override suspend fun touchList(listId: String) = dao.touchList(listId, clock())

    override suspend fun clearItems(listId: String) = db.withTransaction {
        dao.deleteAllItems(listId)
        dao.touchList(listId, clock())
        outbox.dropItemOperations(listId)
        outbox.record(SyncOpType.LIST_CLEAR, listId, flag = false)
    }

    override suspend fun clearPurchasedItems(listId: String) = db.withTransaction {
        dao.deletePurchasedItems(listId)
        dao.touchList(listId, clock())
        outbox.record(SyncOpType.LIST_CLEAR, listId, flag = true)
    }

    override suspend fun uncheckAllItems(listId: String) = db.withTransaction {
        dao.uncheckAllItems(listId, clock())
        dao.touchList(listId, clock())
        outbox.record(SyncOpType.LIST_UNCHECK_ALL, listId)
    }
}

internal fun ListSummaryRow.toDomain() = ShoppingListSummary(
    list = ShoppingList(id, name, createdAt, updatedAt),
    remainingCount = remainingCount,
    totalCount = totalCount,
)

internal fun ShoppingListEntity.toDomain() = ShoppingList(id, name, createdAt, updatedAt)

internal fun ShoppingItemEntity.toDomain() = ShoppingItem(
    id = id,
    listId = listId,
    productId = productId,
    name = name,
    quantity = quantity,
    unit = MeasureUnit.fromCode(unit),
    purchasedQuantity = purchasedQuantity,
    isPurchased = isPurchased,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPinned = isPinned,
)

internal fun ShoppingItem.toEntity() = ShoppingItemEntity(
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
    isPinned = isPinned,
)

internal fun DictionaryProductEntity.toDomain() = Product(
    id = id,
    name = name,
    normalized = normalized,
    key = key,
    category = ProductCategory.fromCode(category),
    isUserDefined = isUserDefined,
    usageCount = usageCount,
)

internal fun Product.toEntity(now: Long) = DictionaryProductEntity(
    id = id,
    name = name,
    normalized = TextNormalizer.normalize(name),
    key = key,
    category = category.code,
    isUserDefined = isUserDefined,
    usageCount = usageCount,
    lastUsedAt = now,
)
