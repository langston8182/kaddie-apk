package com.kaddie.app.domain.sync

import com.kaddie.app.data.remote.AddResult
import com.kaddie.app.data.remote.RemoteAddItem
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.data.remote.ShoppingRemoteDataSource
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.usecase.UnitConflictResolution
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncOutcome {
    /** Everything pushed and pulled (some writes may have been rejected, see [SyncStatus]). */
    DONE,

    /** Temporary failure (offline, server error): try again later with backoff. */
    RETRY,

    /** The API refused our identity: pending writes are kept until it is fixed. */
    AUTH_REQUIRED,
}

/**
 * Local-first synchronization. Room is the source of truth: pending writes (the outbox) are pushed
 * in order, then remote changes are pulled incrementally with `updatedSince`.
 *
 * Deletions are not propagated between devices (the API has no tombstones yet), and this is
 * deliberately not worked around here.
 */
class SyncEngine(
    private val store: SyncStore,
    private val remote: ShoppingRemoteDataSource,
    private val prefs: SyncPreferences,
    private val status: SyncStatus,
    /** Signed-in account (Cognito `sub`), or null: nothing is sent while signed out. */
    private val currentUserId: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private sealed interface Step {
        data object Done : Step
        data class Rejected(val message: String) : Step
        data class Retry(val issue: SyncIssue) : Step
        data object Auth : Step
    }

    suspend fun sync(): SyncOutcome = mutex.withLock {
        val userId = currentUserId()
        if (userId == null) {
            // Signed out: everything stays queued locally until the next sign-in.
            status.setIssue(SyncIssue.AuthRequired)
            return@withLock SyncOutcome.AUTH_REQUIRED
        }
        status.setSyncing(true)
        try {
            if (prefs.userId != userId) {
                // First sync of this account on this device: upload local data, pull everything.
                prefs.userId = userId
                prefs.seeded = false
                prefs.knownRemoteIds = emptySet()
                prefs.productsCursor = null
            }
            if (!prefs.seeded) {
                store.seedOutbox()
                prefs.seeded = true
            }
            var rejected: String? = null
            while (true) {
                val op = store.nextOperation() ?: break
                when (val step = execute(op)) {
                    Step.Done -> store.completeOperation(op.id)
                    is Step.Rejected -> {
                        // Not retryable: the local data stays as is, only the remote copy is skipped.
                        store.completeOperation(op.id)
                        rejected = step.message
                    }
                    is Step.Retry -> {
                        store.recordAttempt(op.id)
                        status.setIssue(step.issue)
                        return@withLock SyncOutcome.RETRY
                    }
                    Step.Auth -> {
                        status.setIssue(SyncIssue.AuthRequired)
                        return@withLock SyncOutcome.AUTH_REQUIRED
                    }
                }
            }
            when (val step = pull()) {
                Step.Done, is Step.Rejected -> Unit
                is Step.Retry -> {
                    status.setIssue(step.issue)
                    return@withLock SyncOutcome.RETRY
                }
                Step.Auth -> {
                    status.setIssue(SyncIssue.AuthRequired)
                    return@withLock SyncOutcome.AUTH_REQUIRED
                }
            }
            status.setIssue(rejected?.let { SyncIssue.Rejected(it) } ?: SyncIssue.None)
            SyncOutcome.DONE
        } finally {
            status.setSyncing(false)
        }
    }

    // ---- push ----

    private suspend fun execute(op: SyncOperation): Step = when (op.type) {
        SyncOpType.LIST_UPSERT -> {
            val list = store.getList(op.entityId)
            if (list == null) Step.Done else remote.upsertList(list.id, list.name).toStep()
        }
        SyncOpType.LIST_DELETE -> remote.deleteList(op.entityId).toStep(notFoundIsDone = true)
        SyncOpType.LIST_CLEAR -> remote.clearItems(op.entityId, op.flag).toStep(notFoundIsDone = true)
        SyncOpType.LIST_UNCHECK_ALL -> remote.uncheckAll(op.entityId).toStep(notFoundIsDone = true)
        SyncOpType.PRODUCT_UPSERT -> {
            val product = store.getProduct(op.entityId)
            if (product == null) Step.Done else remote.upsertProduct(product.id, product.name, product.category).toStep()
        }
        SyncOpType.ITEM_DELETE ->
            remote.deleteItem(requireNotNull(op.parentId), op.entityId).toStep(notFoundIsDone = true)
        SyncOpType.ITEM_PURCHASED -> store.getItem(op.entityId)?.let { pushPurchased(it) } ?: Step.Done
        SyncOpType.ITEM_STATE -> store.getItem(op.entityId)?.let { pushState(it, createIfMissing = true) } ?: Step.Done
        SyncOpType.ITEM_CREATE -> store.getItem(op.entityId)?.let { createItem(it) } ?: Step.Done
    }

    private suspend fun pushPurchased(item: ShoppingItem): Step {
        val result = remote.setPurchased(item.listId, item.id, item.isPurchased)
        return if (result is RemoteResult.Failure && result.error is RemoteError.NotFound) {
            createItem(item)
        } else {
            result.toStep()
        }
    }

    /** Creates the item remotely; the local state is authoritative if the API merged or conflicted. */
    private suspend fun createItem(item: ShoppingItem): Step {
        var request = RemoteAddItem(item.id, item.productId, item.name, item.quantity, item.unit, item.note)
        var result = remote.addItem(item.listId, request)
        if (result is RemoteResult.Failure && result.error is RemoteError.UnitConflict) {
            request = request.copy(unitConflict = UnitConflictResolution.REPLACE)
            result = remote.addItem(item.listId, request)
        }
        val outcome = when (result) {
            is RemoteResult.Failure -> return result.toStep()
            is RemoteResult.Success -> result.value
        }
        var current = item
        if (outcome.item.id != item.id) {
            // The API already had this product in the list under another item id.
            store.rekeyItem(item.id, outcome.item.id)
            current = item.copy(id = outcome.item.id)
        } else if (outcome.result == AddResult.ADDED && item.purchasedQuantity == 0.0) {
            return Step.Done
        }
        return pushState(current, createIfMissing = false)
    }

    /**
     * Makes the remote item equal the local one using the API's edit/purchased endpoints.
     * Partial purchases (0 < bought < requested) are rebuilt as: quantity = bought, mark purchased,
     * then raise the quantity, which the API keeps as "partially bought".
     */
    private suspend fun pushState(item: ShoppingItem, createIfMissing: Boolean): Step {
        val reset = remote.setPurchased(item.listId, item.id, false)
        if (reset is RemoteResult.Failure) {
            return if (reset.error is RemoteError.NotFound && createIfMissing) createItem(item) else reset.toStep()
        }
        val bought = item.purchasedQuantity
        val partial = bought > 0.0 && !item.isPurchased
        val firstQuantity = if (partial) bought else item.quantity

        remote.updateItem(item.listId, item.id, firstQuantity, item.unit, item.note, item.isPinned).let {
            if (it is RemoteResult.Failure) return it.toStep()
        }
        if (bought <= 0.0) return Step.Done
        remote.setPurchased(item.listId, item.id, true).let {
            if (it is RemoteResult.Failure) return it.toStep()
        }
        if (partial) {
            remote.updateItem(item.listId, item.id, item.quantity, item.unit, item.note, item.isPinned).let {
                if (it is RemoteResult.Failure) return it.toStep()
            }
        }
        return Step.Done
    }

    private fun <T> RemoteResult<T>.toStep(notFoundIsDone: Boolean = false): Step = when (this) {
        is RemoteResult.Success -> Step.Done
        is RemoteResult.Failure -> when (val e = error) {
            is RemoteError.Network -> Step.Retry(SyncIssue.Offline)
            is RemoteError.Server -> Step.Retry(SyncIssue.ServerError)
            RemoteError.Unauthorized -> Step.Auth
            RemoteError.NotFound -> if (notFoundIsDone) Step.Done else Step.Rejected("Élément introuvable côté serveur")
            is RemoteError.UnitConflict -> Step.Rejected("Unités incompatibles")
            is RemoteError.Conflict -> Step.Rejected(e.message)
            is RemoteError.Invalid -> Step.Rejected(e.message)
        }
    }

    // ---- pull ----

    /**
     * Reads everything the server has for this account (a few lists, a few dozen products): this is what makes
     * deletions made on another device visible, which an incremental `updatedSince` read cannot do.
     */
    private suspend fun pull(): Step {
        val products = when (val r = remote.fetchProducts(prefs.productsCursor)) {
            is RemoteResult.Failure -> return r.toStep()
            is RemoteResult.Success -> r.value
        }
        store.applyRemoteProducts(products)
        val productsCursor = products.maxOfOrNull { it.updatedAt }

        val lists = when (val r = remote.fetchLists(null)) {
            is RemoteResult.Failure -> return r.toStep()
            is RemoteResult.Success -> r.value
        }
        store.applyRemoteLists(lists)
        val itemIdsByList = mutableMapOf<String, Set<String>>()
        for (list in lists) {
            when (val r = remote.fetchItems(list.id, null)) {
                is RemoteResult.Failure -> return r.toStep()
                is RemoteResult.Success -> {
                    store.applyRemoteItems(list.id, r.value)
                    itemIdsByList[list.id] = r.value.map { it.id }.toSet()
                }
            }
        }

        // Only after a complete read: anything the server had before and no longer has was deleted elsewhere.
        val remoteListIds = lists.map { it.id }.toSet()
        store.applyRemovals(prefs.knownRemoteIds, remoteListIds, itemIdsByList)
        prefs.knownRemoteIds = remoteListIds + itemIdsByList.values.flatten()
        // The cursor only moves once everything was applied, and uses server time (no clock skew).
        productsCursor?.let { prefs.productsCursor = it }
        return Step.Done
    }
}
