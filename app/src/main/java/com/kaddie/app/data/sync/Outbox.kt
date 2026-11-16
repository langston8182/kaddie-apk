package com.kaddie.app.data.sync

import android.content.Context
import com.kaddie.app.data.local.SyncDao
import com.kaddie.app.data.local.SyncOperationEntity
import com.kaddie.app.domain.sync.SyncOpType
import com.kaddie.app.domain.sync.SyncPreferences

/** Appends pending writes to the outbox; always called inside the same transaction as the local write. */
class OutboxRecorder(
    private val dao: SyncDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun record(type: SyncOpType, entityId: String, parentId: String? = null, flag: Boolean = false) {
        dao.insert(SyncOperationEntity(type = type.name, entityId = entityId, parentId = parentId, flag = flag, createdAt = clock(), attempts = 0))
    }

    suspend fun dropItemOperations(listId: String) = dao.deleteItemOperations(listId)

    suspend fun dropListOperations(listId: String) = dao.deleteListOperations(listId)
}

class SharedPrefsSyncPreferences(context: Context) : SyncPreferences {
    private val prefs = context.getSharedPreferences("kaddie_sync", Context.MODE_PRIVATE)

    override var userId: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    override var seeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEDED, value).apply()

    override var listsCursor: Long?
        get() = prefs.getLong(KEY_LISTS, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LISTS) else putLong(KEY_LISTS, value) }.apply()

    override var productsCursor: Long?
        get() = prefs.getLong(KEY_PRODUCTS, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_PRODUCTS) else putLong(KEY_PRODUCTS, value) }.apply()

    private companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_USER = "user_id"
        const val KEY_LISTS = "lists_cursor"
        const val KEY_PRODUCTS = "products_cursor"
    }
}
