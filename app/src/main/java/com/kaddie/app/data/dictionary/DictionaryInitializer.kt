package com.kaddie.app.data.dictionary

import android.content.Context
import androidx.room.withTransaction
import com.kaddie.app.data.local.DictionaryProductEntity
import com.kaddie.app.data.local.KaddieDatabase
import com.kaddie.app.data.local.ProductAliasEntity
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.text.TextNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DictionaryFile(
    val version: Int,
    val categories: List<DictionaryCategory>,
)

@Serializable
internal data class DictionaryCategory(
    val id: String,
    /** Each entry is "Name" or "Name|alias|alias". */
    val products: List<String>,
)

/**
 * Imports the bundled dictionary into the local database on first launch and whenever the bundled
 * version increases. Existing rows (including user-created products and usage counters) are never
 * overwritten, only missing products and aliases are added.
 */
class DictionaryInitializer(
    private val context: Context,
    private val db: KaddieDatabase,
) {
    private val ready = CompletableDeferred<Unit>()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("kaddie_dictionary", Context.MODE_PRIVATE)

    suspend fun awaitReady() = ready.await()

    suspend fun initialize() {
        try {
            withContext(Dispatchers.IO) {
                val file = context.assets.open(ASSET_NAME).bufferedReader().use {
                    json.decodeFromString<DictionaryFile>(it.readText())
                }
                if (file.version > prefs.getInt(KEY_VERSION, 0)) {
                    import(file)
                    prefs.edit().putInt(KEY_VERSION, file.version).apply()
                }
                db.productDao().deleteUnusedAliasDuplicates()
            }
            ready.complete(Unit)
        } catch (e: Exception) {
            ready.completeExceptionally(e)
        }
    }

    private suspend fun import(file: DictionaryFile) {
        val dao = db.productDao()
        db.withTransaction {
            for (category in file.categories) {
                val categoryCode = ProductCategory.fromCode(category.id).code
                for (line in category.products) {
                    val parts = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    val name = parts.firstOrNull() ?: continue
                    val key = TextNormalizer.key(name)
                    if (key.isEmpty()) continue
                    dao.insertProduct(
                        DictionaryProductEntity(
                            id = "builtin:$key",
                            name = name,
                            normalized = TextNormalizer.normalize(name),
                            key = key,
                            category = categoryCode,
                            isUserDefined = false,
                            usageCount = 0,
                            lastUsedAt = 0,
                        ),
                    )
                    val productId = dao.findByKey(key)?.id ?: continue
                    val aliasKeys = parts.drop(1).map(TextNormalizer::key).filter { it.isNotEmpty() && it != key }
                    dao.insertAliases(aliasKeys.distinct().map { ProductAliasEntity(productId, it) })
                }
            }
        }
    }

    private companion object {
        const val ASSET_NAME = "dictionary.json"
        const val KEY_VERSION = "bundled_version"
    }
}
