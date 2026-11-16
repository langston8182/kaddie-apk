package com.kaddie.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary_products",
    indices = [
        Index(value = ["key"], unique = true),
        Index(value = ["normalized"]),
        Index(value = ["usageCount"]),
    ],
)
data class DictionaryProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalized: String,
    val key: String,
    val category: String,
    val isUserDefined: Boolean,
    val usageCount: Int,
    val lastUsedAt: Long,
)

@Entity(
    tableName = "product_aliases",
    primaryKeys = ["productId", "aliasKey"],
    foreignKeys = [
        ForeignKey(
            entity = DictionaryProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["aliasKey"])],
)
data class ProductAliasEntity(
    val productId: String,
    val aliasKey: String,
)

@Entity(tableName = "shopping_lists", indices = [Index(value = ["updatedAt"])])
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "shopping_items",
    foreignKeys = [
        ForeignKey(
            entity = ShoppingListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DictionaryProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["listId", "productId"], unique = true),
        Index(value = ["productId"]),
    ],
)
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val purchasedQuantity: Double,
    val isPurchased: Boolean,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Pending local write to push to the API, in insertion order. See [com.kaddie.app.domain.sync.SyncOperation]. */
@Entity(tableName = "sync_operations")
data class SyncOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val entityId: String,
    val parentId: String?,
    val flag: Boolean,
    val createdAt: Long,
    val attempts: Int,
)

data class ListSummaryRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(name = "remainingCount") val remainingCount: Int,
    @ColumnInfo(name = "totalCount") val totalCount: Int,
)
