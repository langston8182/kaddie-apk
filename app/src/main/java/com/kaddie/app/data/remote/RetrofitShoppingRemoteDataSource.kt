package com.kaddie.app.data.remote

import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.ProductCategory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException
import java.time.Instant

class RetrofitShoppingRemoteDataSource(
    private val api: KaddieApi,
    private val json: Json,
) : ShoppingRemoteDataSource {

    override suspend fun fetchLists(updatedSince: Long?) =
        call({ api.getLists(updatedSince.toIso()) }) { body -> body.orEmpty().map { it.toRemote() } }

    override suspend fun upsertList(id: String, name: String) =
        call({ api.upsertList(id, ListBodyDto(name)) }) { requireNotNull(it).toRemote() }

    override suspend fun deleteList(id: String) = call({ api.deleteList(id) }) { }

    override suspend fun clearItems(listId: String, purchasedOnly: Boolean) =
        call({ api.clearItems(listId, if (purchasedOnly) "true" else null) }) { }

    override suspend fun uncheckAll(listId: String) = call({ api.uncheckAll(listId) }) { }

    override suspend fun fetchItems(listId: String, updatedSince: Long?) =
        call({ api.getItems(listId, updatedSince.toIso()) }) { body -> body.orEmpty().map { it.toRemote() } }

    override suspend fun addItem(listId: String, item: RemoteAddItem) = call({
        api.addItem(
            listId,
            AddItemBodyDto(item.id, item.productId, item.name, item.quantity, item.unit.name, item.note, item.unitConflict?.name),
        )
    }) {
        val body = requireNotNull(it)
        AddItemOutcome(AddResult.entries.firstOrNull { r -> r.name == body.result } ?: AddResult.ADDED, body.item.toRemote())
    }

    override suspend fun updateItem(listId: String, itemId: String, quantity: Double, unit: MeasureUnit, note: String, isPinned: Boolean) =
        call({ api.updateItem(listId, itemId, EditItemBodyDto(quantity, unit.name, note, isPinned)) }) { requireNotNull(it).toRemote() }

    override suspend fun setPurchased(listId: String, itemId: String, purchased: Boolean) =
        call({ api.setPurchased(listId, itemId, PurchasedBodyDto(purchased)) }) { requireNotNull(it).toRemote() }

    override suspend fun deleteItem(listId: String, itemId: String) = call({ api.deleteItem(listId, itemId) }) { }

    override suspend fun fetchProducts(updatedSince: Long?) =
        call({ api.getProducts(updatedSince.toIso()) }) { body -> body.orEmpty().map { it.toRemote() } }

    override suspend fun upsertProduct(id: String, name: String, category: ProductCategory) =
        call({ api.upsertProduct(id, ProductBodyDto(name, category.code)) }) { requireNotNull(it).toRemote() }

    override suspend fun deleteProduct(id: String) = call({ api.deleteProduct(id) }) { }

    override suspend fun fetchTemplates() =
        call({ api.getTemplates() }) { body -> body.orEmpty().map { it.toRemote() } }

    override suspend fun upsertTemplate(id: String, name: String, items: List<RemoteTemplateItem>) =
        call({ api.upsertTemplate(id, TemplateBodyDto(name, items.map { it.toDto() })) }) { requireNotNull(it).toRemote() }

    override suspend fun deleteTemplate(id: String) = call({ api.deleteTemplate(id) }) { }

    private suspend fun <D, T> call(block: suspend () -> Response<D>, map: (D?) -> T): RemoteResult<T> {
        val response = try {
            block()
        } catch (e: IOException) {
            return RemoteResult.Failure(RemoteError.Network(e))
        }
        if (!response.isSuccessful) return RemoteResult.Failure(errorOf(response))
        return try {
            RemoteResult.Success(map(response.body()))
        } catch (e: SerializationException) {
            RemoteResult.Failure(RemoteError.Invalid("Réponse illisible"))
        } catch (e: IllegalArgumentException) {
            RemoteResult.Failure(RemoteError.Invalid("Réponse vide"))
        }
    }

    private fun errorOf(response: Response<*>): RemoteError {
        val status = response.code()
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val error = body?.let { runCatching { json.decodeFromString<ErrorDto>(it) }.getOrNull() }
        val message = error?.message ?: "Erreur $status"
        return when {
            status == 401 || status == 403 -> RemoteError.Unauthorized
            status == 404 -> RemoteError.NotFound
            status == 409 -> {
                val details = runCatching { error?.details?.jsonObject }.getOrNull()
                if (details?.get("code")?.jsonPrimitive?.content == "UNIT_CONFLICT") {
                    RemoteError.UnitConflict(parseExisting(details))
                } else {
                    RemoteError.Conflict(message)
                }
            }
            status == 408 || status == 429 || status >= 500 -> RemoteError.Server(status)
            else -> RemoteError.Invalid(message)
        }
    }

    private fun parseExisting(details: JsonObject): RemoteItem? = runCatching {
        json.decodeFromJsonElement(ItemDto.serializer(), details.getValue("existing")).toRemote()
    }.getOrNull()
}

private fun Long?.toIso(): String? = this?.let { Instant.ofEpochMilli(it).toString() }

private fun String.toMillis(): Long = Instant.parse(this).toEpochMilli()

private fun ListDto.toRemote() = RemoteList(id, name, createdAt.toMillis(), updatedAt.toMillis())

private fun ItemDto.toRemote() = RemoteItem(
    id = id,
    listId = listId,
    productId = productId,
    name = name,
    quantity = quantity,
    unit = MeasureUnit.fromCode(unit),
    purchasedQuantity = purchasedQuantity,
    isPurchased = isPurchased,
    note = note,
    createdAt = createdAt.toMillis(),
    updatedAt = updatedAt.toMillis(),
    isPinned = isPinned,
)

private fun ProductDto.toRemote() =
    RemoteProduct(id, name, ProductCategory.fromCode(category), createdAt.toMillis(), updatedAt.toMillis())

private fun TemplateItemDto.toRemote() =
    RemoteTemplateItem(productId, name, quantity, MeasureUnit.fromCode(unit), note)

private fun RemoteTemplateItem.toDto() = TemplateItemDto(productId, name, quantity, unit.name, note)

private fun TemplateDto.toRemote() = RemoteTemplate(id, name, items.map { it.toRemote() })
