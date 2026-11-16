package com.kaddie.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class ListBodyDto(val name: String)

@Serializable
data class ListDto(val id: String, val name: String, val createdAt: String, val updatedAt: String)

@Serializable
data class ItemDto(
    val id: String,
    val listId: String,
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val purchasedQuantity: Double = 0.0,
    val isPurchased: Boolean = false,
    val isPinned: Boolean? = null,
    val note: String = "",
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AddItemBodyDto(
    val id: String,
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val note: String,
    val unitConflict: String? = null,
)

@Serializable
data class AddItemResponseDto(val result: String, val item: ItemDto)

@Serializable
data class EditItemBodyDto(val quantity: Double, val unit: String, val note: String, val isPinned: Boolean)

@Serializable
data class PurchasedBodyDto(val purchased: Boolean)

@Serializable
data class ProductBodyDto(val name: String, val category: String)

@Serializable
data class ProductDto(val id: String, val name: String, val category: String, val createdAt: String, val updatedAt: String)

@Serializable
data class TemplateItemDto(
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val note: String = "",
)

@Serializable
data class TemplateDto(val id: String, val name: String, val items: List<TemplateItemDto> = emptyList())

@Serializable
data class TemplateBodyDto(val name: String, val items: List<TemplateItemDto>)

@Serializable
data class ErrorDto(val message: String? = null, val details: JsonElement? = null)

/** Raw Retrofit definition of the Kaddie API (see kaddie-api/README.md). */
interface KaddieApi {
    @GET("lists")
    suspend fun getLists(@Query("updatedSince") updatedSince: String?): Response<List<ListDto>>

    @PUT("lists/{id}")
    suspend fun upsertList(@Path("id") id: String, @Body body: ListBodyDto): Response<ListDto>

    @DELETE("lists/{id}")
    suspend fun deleteList(@Path("id") id: String): Response<Unit>

    @DELETE("lists/{id}/items")
    suspend fun clearItems(@Path("id") id: String, @Query("purchased") purchasedOnly: String?): Response<ResponseBody>

    @POST("lists/{id}/uncheck")
    suspend fun uncheckAll(@Path("id") id: String): Response<ResponseBody>

    @GET("lists/{id}/items")
    suspend fun getItems(@Path("id") id: String, @Query("updatedSince") updatedSince: String?): Response<List<ItemDto>>

    @POST("lists/{id}/items")
    suspend fun addItem(@Path("id") id: String, @Body body: AddItemBodyDto): Response<AddItemResponseDto>

    @PUT("lists/{id}/items/{itemId}")
    suspend fun updateItem(@Path("id") id: String, @Path("itemId") itemId: String, @Body body: EditItemBodyDto): Response<ItemDto>

    @PUT("lists/{id}/items/{itemId}/purchased")
    suspend fun setPurchased(@Path("id") id: String, @Path("itemId") itemId: String, @Body body: PurchasedBodyDto): Response<ItemDto>

    @DELETE("lists/{id}/items/{itemId}")
    suspend fun deleteItem(@Path("id") id: String, @Path("itemId") itemId: String): Response<Unit>

    @GET("products")
    suspend fun getProducts(@Query("updatedSince") updatedSince: String?): Response<List<ProductDto>>

    @PUT("products/{id}")
    suspend fun upsertProduct(@Path("id") id: String, @Body body: ProductBodyDto): Response<ProductDto>

    @GET("templates")
    suspend fun getTemplates(): Response<List<TemplateDto>>

    @PUT("templates/{id}")
    suspend fun upsertTemplate(@Path("id") id: String, @Body body: TemplateBodyDto): Response<TemplateDto>

    @DELETE("templates/{id}")
    suspend fun deleteTemplate(@Path("id") id: String): Response<Unit>
}
