package com.kaddie.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/** Version 3 briefly stored templates on the device; they now live on the server only. */
@DeleteTable.Entries(DeleteTable(tableName = "list_templates"), DeleteTable(tableName = "list_template_items"))
class RemoveLocalTemplatesMigration : AutoMigrationSpec

@Database(
    entities = [
        DictionaryProductEntity::class,
        ProductAliasEntity::class,
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
        SyncOperationEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = RemoveLocalTemplatesMigration::class),
        AutoMigration(from = 4, to = 5),
    ],
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
