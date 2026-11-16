package com.kaddie.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insert(op: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations ORDER BY id ASC LIMIT 1")
    suspend fun first(): SyncOperationEntity?

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sync_operations SET attempts = attempts + 1 WHERE id = :id")
    suspend fun recordAttempt(id: Long)

    @Query("SELECT COUNT(*) FROM sync_operations")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(id) FROM sync_operations")
    fun observeLastId(): Flow<Long?>

    @Query("SELECT DISTINCT entityId FROM sync_operations")
    suspend fun pendingEntityIds(): List<String>

    @Query("UPDATE sync_operations SET entityId = :newId WHERE entityId = :oldId")
    suspend fun rekey(oldId: String, newId: String)

    @Query(
        "DELETE FROM sync_operations WHERE parentId = :listId " +
            "AND type IN ('ITEM_CREATE', 'ITEM_STATE', 'ITEM_PURCHASED', 'ITEM_DELETE')",
    )
    suspend fun deleteItemOperations(listId: String)

    @Query("DELETE FROM sync_operations WHERE entityId = :listId AND type IN ('LIST_UPSERT', 'LIST_CLEAR', 'LIST_UNCHECK_ALL')")
    suspend fun deleteListOperations(listId: String)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM dictionary_products")
    suspend fun getAll(): List<DictionaryProductEntity>

    @Query("SELECT * FROM product_aliases")
    suspend fun getAllAliases(): List<ProductAliasEntity>

    @Query("SELECT * FROM dictionary_products WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): DictionaryProductEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProduct(product: DictionaryProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAliases(aliases: List<ProductAliasEntity>)

    @Query("UPDATE dictionary_products SET usageCount = usageCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun incrementUsage(id: String, now: Long)

    @Query("SELECT * FROM dictionary_products WHERE id = :id")
    suspend fun findById(id: String): DictionaryProductEntity?

    @Query("SELECT * FROM dictionary_products WHERE isUserDefined = 1")
    suspend fun getUserDefined(): List<DictionaryProductEntity>

    @Query(
        "UPDATE dictionary_products SET name = :name, normalized = :normalized, category = :category " +
            "WHERE id = :id AND isUserDefined = 1",
    )
    suspend fun updateUserProduct(id: String, name: String, normalized: String, category: String)
}

@Dao
interface ShoppingListDao {
    @Query(
        """
        SELECT l.id AS id, l.name AS name, l.createdAt AS createdAt, l.updatedAt AS updatedAt,
            (SELECT COUNT(*) FROM shopping_items i WHERE i.listId = l.id AND i.isPurchased = 0) AS remainingCount,
            (SELECT COUNT(*) FROM shopping_items i WHERE i.listId = l.id) AS totalCount
        FROM shopping_lists l
        ORDER BY l.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ListSummaryRow>>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    fun observeList(id: String): Flow<ShoppingListEntity?>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    suspend fun getList(id: String): ShoppingListEntity?

    @Query(
        """
        SELECT * FROM shopping_items WHERE listId = :listId
        ORDER BY isPurchased ASC,
            CASE WHEN isPurchased = 0 THEN createdAt END ASC,
            CASE WHEN isPurchased = 1 THEN updatedAt END DESC
        """,
    )
    fun observeItems(listId: String): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items WHERE listId = :listId ORDER BY createdAt ASC")
    suspend fun getItems(listId: String): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    suspend fun getItem(id: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_items WHERE listId = :listId AND productId = :productId LIMIT 1")
    suspend fun findItem(listId: String, productId: String): ShoppingItemEntity?

    @Insert
    suspend fun insertList(list: ShoppingListEntity)

    @Insert
    suspend fun insertItem(item: ShoppingItemEntity)

    @Insert
    suspend fun insertItems(items: List<ShoppingItemEntity>)

    @Update
    suspend fun updateItem(item: ShoppingItemEntity)

    @Query("UPDATE shopping_lists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun renameList(id: String, name: String, now: Long)

    @Query("UPDATE shopping_lists SET updatedAt = :now WHERE id = :id")
    suspend fun touchList(id: String, now: Long)

    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteList(id: String)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM shopping_items WHERE listId = :listId")
    suspend fun deleteAllItems(listId: String)

    @Query("DELETE FROM shopping_items WHERE listId = :listId AND isPurchased = 1")
    suspend fun deletePurchasedItems(listId: String)

    @Query("UPDATE shopping_items SET isPurchased = 0, purchasedQuantity = 0, updatedAt = :now " +
        "WHERE listId = :listId AND (isPurchased = 1 OR purchasedQuantity > 0)")
    suspend fun uncheckAllItems(listId: String, now: Long)

    @Query("SELECT * FROM shopping_lists")
    suspend fun getAllLists(): List<ShoppingListEntity>

    @Query("SELECT * FROM shopping_items ORDER BY createdAt ASC")
    suspend fun getAllItems(): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListIfAbsent(list: ShoppingListEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemIfAbsent(item: ShoppingItemEntity)

    @Query("UPDATE shopping_lists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun applyRemoteList(id: String, name: String, updatedAt: Long)

    @Query("UPDATE shopping_items SET id = :newId WHERE id = :oldId")
    suspend fun rekeyItem(oldId: String, newId: String)
}
