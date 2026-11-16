package com.kaddie.app.data.repository

import androidx.room.withTransaction
import com.kaddie.app.data.local.DictionaryProductEntity
import com.kaddie.app.data.local.KaddieDatabase
import com.kaddie.app.data.local.ShoppingItemEntity
import com.kaddie.app.data.local.ShoppingListEntity
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.data.remote.RemoteTemplate
import com.kaddie.app.data.remote.RemoteTemplateItem
import com.kaddie.app.data.remote.ShoppingRemoteDataSource
import com.kaddie.app.data.sync.OutboxRecorder
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.model.TemplateItem
import com.kaddie.app.domain.repository.TemplateRepository
import com.kaddie.app.domain.sync.SyncOpType
import com.kaddie.app.domain.text.TextNormalizer
import java.util.UUID

/**
 * Templates are read and written on the server only, so nothing about them is stored on the device.
 * Only the list created from a template is local (and synchronized like any other list).
 */
class RemoteTemplateRepository(
    private val remote: ShoppingRemoteDataSource,
    private val db: KaddieDatabase,
    private val outbox: OutboxRecorder,
    private val onDictionaryChanged: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : TemplateRepository {
    private val lists = db.shoppingListDao()
    private val products = db.productDao()

    override suspend fun list(): RemoteResult<List<ListTemplate>> =
        remote.fetchTemplates().map { templates -> templates.map { it.toDomain() } }

    override suspend fun saveFromList(listId: String, name: String): RemoteResult<ListTemplate> {
        if (lists.getList(listId) == null) return RemoteResult.Failure(RemoteError.NotFound)
        val items = lists.getItems(listId).map {
            RemoteTemplateItem(it.productId, it.name, it.quantity, com.kaddie.app.domain.model.MeasureUnit.fromCode(it.unit), it.note)
        }
        return remote.upsertTemplate(UUID.randomUUID().toString(), name.trim(), items).map { it.toDomain() }
    }

    override suspend fun delete(templateId: String): RemoteResult<Unit> = remote.deleteTemplate(templateId)

    override suspend fun createListFromTemplate(template: ListTemplate, name: String): String {
        var productCreated = false
        val listId = db.withTransaction {
            val now = clock()
            val list = ShoppingListEntity(UUID.randomUUID().toString(), name.trim(), now, now)
            lists.insertList(list)
            outbox.record(SyncOpType.LIST_UPSERT, list.id)
            val used = mutableSetOf<String>()
            template.items.forEachIndexed { index, t ->
                val (productId, created) = ensureProduct(t) ?: return@forEachIndexed
                if (created) productCreated = true
                if (!used.add(productId)) return@forEachIndexed
                val item = ShoppingItemEntity(
                    id = UUID.randomUUID().toString(),
                    listId = list.id,
                    productId = productId,
                    name = t.name,
                    quantity = t.quantity,
                    unit = t.unit.name,
                    purchasedQuantity = 0.0,
                    isPurchased = false,
                    note = t.note,
                    // Distinct creation times keep the template's order.
                    createdAt = now + index,
                    updatedAt = now,
                )
                lists.insertItem(item)
                outbox.record(SyncOpType.ITEM_CREATE, item.id, list.id)
            }
            list.id
        }
        if (productCreated) onDictionaryChanged()
        return listId
    }

    /** The product may not exist on this device (template saved elsewhere): reuse an equivalent one or create it. */
    private suspend fun ensureProduct(item: TemplateItem): Pair<String, Boolean>? {
        products.findById(item.productId)?.let { return it.id to false }
        val key = TextNormalizer.key(item.name)
        if (key.isEmpty()) return null
        products.findByKey(key)?.let { return it.id to false }
        products.findByAliasKey(key)?.let { return it.id to false }
        products.insertProduct(
            DictionaryProductEntity(
                id = item.productId,
                name = item.name,
                normalized = TextNormalizer.normalize(item.name),
                key = key,
                category = "autres",
                isUserDefined = true,
                usageCount = 0,
                lastUsedAt = clock(),
            ),
        )
        outbox.record(SyncOpType.PRODUCT_UPSERT, item.productId)
        return item.productId to true
    }
}

private fun RemoteTemplate.toDomain() =
    ListTemplate(id, name, items.map { TemplateItem(it.productId, it.name, it.quantity, it.unit, it.note) })

private inline fun <T, R> RemoteResult<T>.map(transform: (T) -> R): RemoteResult<R> = when (this) {
    is RemoteResult.Success -> RemoteResult.Success(transform(value))
    is RemoteResult.Failure -> this
}
