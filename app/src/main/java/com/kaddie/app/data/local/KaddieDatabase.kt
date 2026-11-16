package com.kaddie.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DictionaryProductEntity::class,
        ProductAliasEntity::class,
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
        SyncOperationEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class KaddieDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun syncDao(): SyncDao

    companion object {
        fun create(context: Context): KaddieDatabase =
            Room.databaseBuilder(context, KaddieDatabase::class.java, "kaddie.db").build()
    }
}
