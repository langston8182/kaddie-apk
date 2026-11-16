package com.kaddie.app

import com.kaddie.app.data.remote.AddItemOutcome
import com.kaddie.app.data.remote.AddResult
import com.kaddie.app.data.remote.RemoteAddItem
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteItem
import com.kaddie.app.data.remote.RemoteList
import com.kaddie.app.data.remote.RemoteProduct
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.data.remote.ShoppingRemoteDataSource
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import com.kaddie.app.domain.sync.LocalItemRef
import com.kaddie.app.domain.sync.SyncEngine
import com.kaddie.app.domain.sync.SyncIssue
import com.kaddie.app.domain.sync.SyncOpType
import com.kaddie.app.domain.sync.SyncOperation
import com.kaddie.app.domain.sync.SyncOutcome
import com.kaddie.app.domain.sync.SyncPreferences
import com.kaddie.app.domain.sync.SyncStatus
import com.kaddie.app.domain.sync.SyncStore
import com.kaddie.app.domain.usecase.QuantityMerger
import com.kaddie.app.domain.usecase.UnitConflictResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakePrefs : SyncPreferences {
    override var userId: String? = "user-1"
    override var seeded = true
    override var knownRemoteIds: Set<String> = emptySet()
    override var productsCursor: Long? = null
}

private class FakeStore : SyncStore {
    val ops = mutableListOf<SyncOperation>()
    val attempts = mutableMapOf<Long, Int>()
    val lists = mutableMapOf<String, ShoppingList>()
    val items = mutableMapOf<String, ShoppingItem>()
    val rekeys = mutableListOf<Pair<String, String>>()
    val appliedProducts = mutableListOf<RemoteProduct>()
    val appliedLists = mutableListOf<RemoteList>()
    val appliedItems = mutableMapOf<String, List<RemoteItem>>()
    var seedCalls = 0
    private var nextId = 1L

    fun enqueue(type: SyncOpType, entityId: String, parentId: String? = null, flag: Boolean = false) {
        ops += SyncOperation(nextId++, type, entityId, parentId, flag)
    }

    private val pending = MutableStateFlow(0)
    override suspend fun nextOperation() = ops.firstOrNull()
    override suspend fun completeOperation(id: Long) { ops.removeAll { it.id == id } }
    override suspend fun recordAttempt(id: Long) { attempts[id] = (attempts[id] ?: 0) + 1 }
    override suspend fun seedOutbox() { seedCalls++ }
    override suspend fun getList(id: String) = lists[id]
    override suspend fun getItem(id: String) = items[id]
    override suspend fun getProduct(id: String): Product? = null
    override suspend fun hasList(id: String) = id in lists
    override suspend fun rekeyItem(oldId: String, newId: String) {
        rekeys += oldId to newId
        items.remove(oldId)?.let { items[newId] = it.copy(id = newId) }
    }
    override suspend fun applyRemoteProducts(products: List<RemoteProduct>) { appliedProducts += products }
    override suspend fun applyRemoteLists(lists: List<RemoteList>) {
        appliedLists += lists
        lists.forEach { this.lists[it.id] = ShoppingList(it.id, it.name, it.createdAt, it.updatedAt) }
    }
    override suspend fun applyRemoteItems(listId: String, items: List<RemoteItem>) { appliedItems[listId] = items }
    var removalsCall: Triple<Set<String>, Set<String>, Map<String, Set<String>>>? = null
    override suspend fun applyRemovals(
        knownRemoteIds: Set<String>,
        remoteListIds: Set<String>,
        remoteItemIdsByList: Map<String, Set<String>>,
    ): com.kaddie.app.domain.sync.Removals {
        removalsCall = Triple(knownRemoteIds, remoteListIds, remoteItemIdsByList)
        return com.kaddie.app.domain.sync.Removals(emptyList(), emptyList())
    }
    override fun observePendingCount(): Flow<Int> = pending.map { ops.size }
    override fun observeLastOperationId(): Flow<Long?> = pending.map { ops.lastOrNull()?.id }
}

/** In-memory API following the real server's rules (merge, edit and purchased semantics). */
private class FakeServer : ShoppingRemoteDataSource {
    val lists = mutableMapOf<String, String>()
    val items = mutableMapOf<String, RemoteItem>()
    val calls = mutableListOf<String>()
    val failures = ArrayDeque<Pair<String, RemoteError>>()
    var productsToPull: List<RemoteProduct> = emptyList()
    var listsToPull: List<RemoteList> = emptyList()
    var itemsToPull: Map<String, List<RemoteItem>> = emptyMap()
    val itemSinceArgs = mutableMapOf<String, Long?>()
    val addRequests = mutableListOf<RemoteAddItem>()

    private inline fun <T> fail(name: String, value: () -> T): RemoteResult<T> {
        calls += name
        val index = failures.indexOfFirst { it.first == name }
        if (index >= 0) return RemoteResult.Failure(failures.removeAt(index).second)
        return RemoteResult.Success(value())
    }

    private fun notFound(): RemoteResult.Failure = RemoteResult.Failure(RemoteError.NotFound)

    override suspend fun fetchLists(updatedSince: Long?) = fail("fetchLists") { listsToPull }
    override suspend fun upsertList(id: String, name: String) = fail("upsertList") {
        lists[id] = name
        RemoteList(id, name, 1, 1)
    }
    override suspend fun deleteList(id: String) = fail("deleteList") { lists.remove(id) ?: return notFound(); Unit }
    override suspend fun clearItems(listId: String, purchasedOnly: Boolean) = fail("clearItems") {
        if (listId !in lists) return notFound()
        items.values.removeAll { it.listId == listId && (!purchasedOnly || it.isPurchased) }
        Unit
    }
    override suspend fun uncheckAll(listId: String) = fail("uncheckAll") { Unit }
    override suspend fun fetchItems(listId: String, updatedSince: Long?) = fail("fetchItems") {
        itemSinceArgs[listId] = updatedSince
        itemsToPull[listId].orEmpty()
    }

    override suspend fun addItem(listId: String, item: RemoteAddItem): RemoteResult<AddItemOutcome> {
        addRequests += item
        return fail("addItem") {
            if (listId !in lists) return notFound()
            items[item.id]?.let { return@fail AddItemOutcome(AddResult.UNCHANGED, it) }
            val existing = items.values.firstOrNull { it.listId == listId && it.productId == item.productId }
            if (existing == null) {
                val created = RemoteItem(item.id, listId, item.productId, item.name, item.quantity, item.unit, 0.0, false, item.note, 1, 1)
                items[item.id] = created
                AddItemOutcome(AddResult.ADDED, created)
            } else if (existing.unit.isCompatibleWith(item.unit)) {
                val sum = QuantityMerger.add(
                    com.kaddie.app.domain.usecase.Quantity(existing.quantity, existing.unit),
                    com.kaddie.app.domain.usecase.Quantity(item.quantity, item.unit),
                )!!
                val merged = existing.copy(quantity = sum.amount, unit = sum.unit)
                items[existing.id] = merged
                AddItemOutcome(AddResult.MERGED, merged)
            } else if (item.unitConflict == UnitConflictResolution.REPLACE) {
                val replaced = existing.copy(quantity = item.quantity, unit = item.unit, purchasedQuantity = 0.0, isPurchased = false)
                items[existing.id] = replaced
                AddItemOutcome(AddResult.MERGED, replaced)
            } else {
                return RemoteResult.Failure(RemoteError.UnitConflict(existing))
            }
        }
    }

    override suspend fun updateItem(listId: String, itemId: String, quantity: Double, unit: MeasureUnit, note: String, isPinned: Boolean) =
        fail("updateItem") {
            val item = items[itemId] ?: return notFound()
            val purchased = when {
                unit != item.unit -> if (item.isPurchased) quantity else 0.0
                item.isPurchased -> item.quantity.coerceAtMost(quantity)
                else -> item.purchasedQuantity.coerceAtMost(quantity)
            }
            val updated = item.copy(
                quantity = quantity, unit = unit, note = note, purchasedQuantity = purchased, isPinned = isPinned,
                isPurchased = QuantityMerger.isFullyPurchased(quantity, purchased),
            )
            items[itemId] = updated
            updated
        }

    override suspend fun setPurchased(listId: String, itemId: String, purchased: Boolean) = fail("setPurchased") {
        val item = items[itemId] ?: return notFound()
        val updated = item.copy(isPurchased = purchased, purchasedQuantity = if (purchased) item.quantity else 0.0)
        items[itemId] = updated
        updated
    }

    override suspend fun deleteItem(listId: String, itemId: String) = fail("deleteItem") { items.remove(itemId) ?: return notFound(); Unit }
    override suspend fun fetchProducts(updatedSince: Long?) = fail("fetchProducts") { productsToPull }
    override suspend fun upsertProduct(id: String, name: String, category: ProductCategory) =
        fail("upsertProduct") { RemoteProduct(id, name, category, 1, 1) }
    override suspend fun fetchTemplates() = fail("fetchTemplates") { emptyList<com.kaddie.app.data.remote.RemoteTemplate>() }
    override suspend fun upsertTemplate(id: String, name: String, items: List<com.kaddie.app.data.remote.RemoteTemplateItem>) =
        fail("upsertTemplate") { com.kaddie.app.data.remote.RemoteTemplate(id, name, items) }
    override suspend fun deleteTemplate(id: String) = fail("deleteTemplate") { Unit }
}

class SyncEngineTests {
    private lateinit var store: FakeStore
    private lateinit var server: FakeServer
    private lateinit var prefs: FakePrefs
    private lateinit var status: SyncStatus
    private lateinit var engine: SyncEngine

    private var userId: String? = "user-1"

    @Before fun setUp() {
        store = FakeStore()
        server = FakeServer()
        prefs = FakePrefs()
        status = SyncStatus()
        userId = "user-1"
        engine = SyncEngine(store, server, prefs, status, { userId }) { 1_000_000L }
    }

    @Test fun signedOutSendsNothingAndKeepsOperations() = runBlocking {
        localList()
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        userId = null

        assertEquals(SyncOutcome.AUTH_REQUIRED, engine.sync())

        assertTrue(server.calls.isEmpty())
        assertEquals(1, store.ops.size)
        assertEquals(SyncIssue.AuthRequired, status.issue.value)

        userId = "user-1"
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertTrue(store.ops.isEmpty())
        assertEquals(SyncIssue.None, status.issue.value)
    }

    @Test fun anotherAccountReseedsLocalDataAndPullsEverything() = runBlocking {
        prefs.knownRemoteIds = setOf("old-list", "old-item")
        prefs.productsCursor = 456L
        userId = "user-2"

        engine.sync()

        assertEquals(1, store.seedCalls)
        assertEquals("user-2", prefs.userId)
        assertEquals(emptySet<String>(), store.removalsCall?.first) // nothing of the previous account is "known"
        assertNull(prefs.productsCursor)
    }

    private fun localList(id: String = "L1", name: String = "Courses") {
        store.lists[id] = ShoppingList(id, name, 0, 0)
    }

    private fun localItem(
        id: String = "I1", listId: String = "L1", productId: String = "p1", name: String = "Lait",
        quantity: Double = 2.0, unit: MeasureUnit = MeasureUnit.L, bought: Double = 0.0, purchased: Boolean = false,
    ): ShoppingItem {
        val item = ShoppingItem(id, listId, productId, name, quantity, unit, bought, purchased, "", 0, 0)
        store.items[id] = item
        return item
    }

    @Test fun pushesOperationsInOrderThenPulls() = runBlocking {
        localList()
        localItem()
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        store.enqueue(SyncOpType.ITEM_CREATE, "I1", "L1")

        assertEquals(SyncOutcome.DONE, engine.sync())

        assertEquals(listOf("upsertList", "addItem", "fetchProducts", "fetchLists"), server.calls)
        assertEquals(2.0, server.items.getValue("I1").quantity, 0.0)
        assertTrue(store.ops.isEmpty())
        assertEquals(SyncIssue.None, status.issue.value)
    }

    @Test fun seedsExistingLocalDataOnlyOnce() = runBlocking {
        prefs.seeded = false
        engine.sync()
        engine.sync()
        assertEquals(1, store.seedCalls)
        assertTrue(prefs.seeded)
    }

    @Test fun offlineKeepsTheOperationAndRetries() = runBlocking {
        localList()
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        server.failures += "upsertList" to RemoteError.Network(null)

        assertEquals(SyncOutcome.RETRY, engine.sync())
        assertEquals(1, store.ops.size)
        assertEquals(1, store.attempts.values.single())
        assertEquals(SyncIssue.Offline, status.issue.value)
        assertTrue(store.lists.containsKey("L1"))

        assertEquals(SyncOutcome.DONE, engine.sync())
        assertTrue(store.ops.isEmpty())
        assertEquals(SyncIssue.None, status.issue.value)
        assertEquals("Courses", server.lists["L1"])
    }

    @Test fun serverErrorsAreRetried() = runBlocking {
        localList()
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        server.failures += "upsertList" to RemoteError.Server(500)
        assertEquals(SyncOutcome.RETRY, engine.sync())
        assertEquals(SyncIssue.ServerError, status.issue.value)
        assertEquals(1, store.ops.size)
    }

    @Test fun unauthorizedStopsWithoutLosingOperations() = runBlocking {
        localList()
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        server.failures += "upsertList" to RemoteError.Unauthorized
        assertEquals(SyncOutcome.AUTH_REQUIRED, engine.sync())
        assertEquals(1, store.ops.size)
        assertEquals(SyncIssue.AuthRequired, status.issue.value)
    }

    @Test fun rejectedWriteIsSkippedWithoutBlockingTheQueue() = runBlocking {
        localList()
        localList("L2", "Autre")
        store.enqueue(SyncOpType.LIST_UPSERT, "L1")
        store.enqueue(SyncOpType.LIST_UPSERT, "L2")
        server.failures += "upsertList" to RemoteError.Invalid("Nom invalide")

        assertEquals(SyncOutcome.DONE, engine.sync())
        assertTrue(store.ops.isEmpty())
        assertEquals("Autre", server.lists["L2"])
        assertTrue(status.issue.value is SyncIssue.Rejected)
        assertEquals(2, store.lists.size) // local data untouched
    }

    @Test fun deletingSomethingAlreadyGoneIsFine() = runBlocking {
        store.enqueue(SyncOpType.LIST_DELETE, "L1")
        store.enqueue(SyncOpType.ITEM_DELETE, "I1", "L1")
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertEquals(SyncIssue.None, status.issue.value)
    }

    @Test fun operationsOnLocallyDeletedItemsAreSkipped() = runBlocking {
        store.enqueue(SyncOpType.ITEM_STATE, "ghost", "L1")
        assertEquals(SyncOutcome.DONE, engine.sync())
        assertFalse(server.calls.contains("setPurchased"))
    }

    @Test fun unitConflictIsResentWithReplace() = runBlocking {
        localList()
        server.lists["L1"] = "Courses"
        server.items["S1"] = RemoteItem("S1", "L1", "p1", "Lait", 1.0, MeasureUnit.PIECE, 0.0, false, "", 1, 1)
        localItem(quantity = 2.0, unit = MeasureUnit.L)
        store.enqueue(SyncOpType.ITEM_CREATE, "I1", "L1")

        assertEquals(SyncOutcome.DONE, engine.sync())

        assertEquals(listOf(null, UnitConflictResolution.REPLACE), server.addRequests.map { it.unitConflict })
        val remote = server.items.getValue("S1")
        assertEquals(2.0, remote.quantity, 0.0)
        assertEquals(MeasureUnit.L, remote.unit)
        assertEquals(listOf("I1" to "S1"), store.rekeys)
    }

    @Test fun mergedItemAdoptsTheServerId() = runBlocking {
        localList()
        server.lists["L1"] = "Courses"
        server.items["S1"] = RemoteItem("S1", "L1", "p1", "Lait", 1.0, MeasureUnit.L, 0.0, false, "", 1, 1)
        localItem(quantity = 3.0, unit = MeasureUnit.L)
        store.enqueue(SyncOpType.ITEM_CREATE, "I1", "L1")

        engine.sync()

        assertEquals(listOf("I1" to "S1"), store.rekeys)
        assertEquals(3.0, server.items.getValue("S1").quantity, 0.0) // local state wins, no inflation
        assertEquals(1, server.items.size)
    }

    @Test fun partialPurchaseIsReproducedOnTheServer() = runBlocking {
        localList()
        server.lists["L1"] = "Courses"
        localItem(quantity = 2.0, unit = MeasureUnit.PIECE, bought = 1.0, purchased = false)
        store.enqueue(SyncOpType.ITEM_CREATE, "I1", "L1")

        engine.sync()

        val remote = server.items.getValue("I1")
        assertEquals(2.0, remote.quantity, 0.0)
        assertEquals(1.0, remote.purchasedQuantity, 0.0)
        assertFalse(remote.isPurchased)
    }

    @Test fun fullyPurchasedAndEditedStateIsPushed() = runBlocking {
        localList()
        server.lists["L1"] = "Courses"
        server.items["I1"] = RemoteItem("I1", "L1", "p1", "Lait", 1.0, MeasureUnit.L, 0.0, false, "", 1, 1)
        localItem(quantity = 3.0, unit = MeasureUnit.KG, bought = 3.0, purchased = true)
        store.enqueue(SyncOpType.ITEM_STATE, "I1", "L1")

        engine.sync()

        val remote = server.items.getValue("I1")
        assertEquals(3.0, remote.quantity, 0.0)
        assertEquals(MeasureUnit.KG, remote.unit)
        assertTrue(remote.isPurchased)
    }

    @Test fun purchaseToggleIsASingleCallAndFallsBackToCreate() = runBlocking {
        localList()
        server.lists["L1"] = "Courses"
        server.items["I1"] = RemoteItem("I1", "L1", "p1", "Lait", 2.0, MeasureUnit.L, 0.0, false, "", 1, 1)
        localItem(bought = 2.0, purchased = true)
        store.enqueue(SyncOpType.ITEM_PURCHASED, "I1", "L1")
        engine.sync()
        assertEquals(1, server.calls.count { it == "setPurchased" })
        assertTrue(server.items.getValue("I1").isPurchased)

        // Item unknown to the server (never created): created from local state.
        server.items.clear()
        store.enqueue(SyncOpType.ITEM_PURCHASED, "I1", "L1")
        engine.sync()
        assertTrue(server.items.getValue("I1").isPurchased)
    }

    @Test fun bulkListOperationsAreForwarded() = runBlocking {
        server.lists["L1"] = "Courses"
        server.items["I1"] = RemoteItem("I1", "L1", "p1", "Lait", 1.0, MeasureUnit.L, 1.0, true, "", 1, 1)
        store.enqueue(SyncOpType.LIST_CLEAR, "L1", flag = true)
        store.enqueue(SyncOpType.LIST_UNCHECK_ALL, "L1")
        engine.sync()
        assertTrue(server.items.isEmpty())
        assertTrue(server.calls.containsAll(listOf("clearItems", "uncheckAll")))
    }

    @Test fun pullsEverythingAndAdvancesTheProductCursor() = runBlocking {
        localList("KNOWN", "Connue")
        prefs.productsCursor = 100L
        server.productsToPull = listOf(RemoteProduct("pp", "Kombucha", ProductCategory.BOISSONS, 1, 500))
        server.listsToPull = listOf(RemoteList("KNOWN", "Connue", 1, 950_000), RemoteList("NEW", "Nouvelle", 1, 970_000))
        server.itemsToPull = mapOf("NEW" to listOf(RemoteItem("N1", "NEW", "pp", "Kombucha", 1.0, MeasureUnit.PIECE, 0.0, false, "", 1, 1)))

        assertEquals(SyncOutcome.DONE, engine.sync())

        assertEquals(listOf("pp"), store.appliedProducts.map { it.id })
        assertEquals(setOf("KNOWN", "NEW"), store.appliedLists.map { it.id }.toSet())
        assertEquals(listOf("N1"), store.appliedItems.getValue("NEW").map { it.id })
        // Full reads (no updatedSince), so that lists and products deleted elsewhere can be noticed.
        assertNull(server.itemSinceArgs["KNOWN"])
        assertNull(server.itemSinceArgs["NEW"])
        assertEquals(500L, prefs.productsCursor)
    }

    @Test fun reportsWhatTheServerHasAndRemembersIt() = runBlocking {
        prefs.knownRemoteIds = setOf("L1", "A", "B")
        server.listsToPull = listOf(RemoteList("L1", "Courses", 1, 1))
        server.itemsToPull = mapOf("L1" to listOf(RemoteItem("B", "L1", "p", "Pain", 1.0, MeasureUnit.PIECE, 0.0, false, "", 1, 1)))

        engine.sync()

        val call = store.removalsCall!!
        assertEquals(setOf("L1", "A", "B"), call.first) // known before this read
        assertEquals(setOf("L1"), call.second)
        assertEquals(mapOf("L1" to setOf("B")), call.third)
        assertEquals(setOf("L1", "B"), prefs.knownRemoteIds) // "A" is gone from the server now
    }

    @Test fun failedPullChangesNothing() = runBlocking {
        prefs.knownRemoteIds = setOf("L1", "A")
        prefs.productsCursor = 5L
        server.listsToPull = listOf(RemoteList("L1", "Courses", 1, 1))
        server.failures += "fetchItems" to RemoteError.Network(null)

        assertEquals(SyncOutcome.RETRY, engine.sync())

        assertNull(store.removalsCall) // an incomplete read must never delete anything
        assertEquals(setOf("L1", "A"), prefs.knownRemoteIds)
        assertEquals(5L, prefs.productsCursor)
    }
}

class ReconciliationTests {
    private fun removals(
        known: Set<String>,
        pending: Set<String> = emptySet(),
        lists: List<String>,
        items: List<LocalItemRef> = emptyList(),
        remoteLists: Set<String>,
        remoteItems: Map<String, Set<String>> = emptyMap(),
    ) = com.kaddie.app.domain.sync.Reconciliation.removals(known, pending, lists, items, remoteLists, remoteItems)

    @Test fun anItemDeletedOnAnotherDeviceIsRemovedHere() {
        val r = removals(
            known = setOf("L", "lait", "yaourt"),
            lists = listOf("L"),
            items = listOf(LocalItemRef("lait", "L"), LocalItemRef("yaourt", "L")),
            remoteLists = setOf("L"),
            remoteItems = mapOf("L" to setOf("yaourt")),
        )
        assertEquals(listOf("lait"), r.itemIds)
        assertTrue(r.listIds.isEmpty())
    }

    @Test fun aListDeletedElsewhereIsRemovedHere() {
        val r = removals(
            known = setOf("L", "lait"),
            lists = listOf("L", "OTHER"),
            items = listOf(LocalItemRef("lait", "L")),
            remoteLists = setOf("OTHER"),
        )
        assertEquals(listOf("L"), r.listIds)
        assertTrue(r.itemIds.isEmpty()) // its products go with it (cascade)
    }

    @Test fun neverSeenOnTheServerMeansNeverDeleted() {
        // Created here and still unsent, or refused by the API: absent from the server but not deleted there.
        val r = removals(
            known = setOf("L"),
            lists = listOf("L", "NEW"),
            items = listOf(LocalItemRef("fresh", "L")),
            remoteLists = setOf("L"),
            remoteItems = mapOf("L" to emptySet()),
        )
        assertTrue(r.listIds.isEmpty())
        assertTrue(r.itemIds.isEmpty())
    }

    @Test fun pendingLocalWritesAreKept() {
        val r = removals(
            known = setOf("L", "lait", "pain"),
            pending = setOf("lait", "L2"),
            lists = listOf("L", "L2"),
            items = listOf(LocalItemRef("lait", "L"), LocalItemRef("pain", "L")),
            remoteLists = setOf("L"),
            remoteItems = mapOf("L" to emptySet()),
        )
        assertEquals(listOf("pain"), r.itemIds) // "lait" was changed offline: it wins
        assertTrue(r.listIds.isEmpty()) // "L2" has a pending write
    }

    @Test fun nothingIsKnownOnTheFirstSyncSoNothingIsDeleted() {
        val r = removals(
            known = emptySet(),
            lists = listOf("L"),
            items = listOf(LocalItemRef("lait", "L")),
            remoteLists = emptySet(),
        )
        assertTrue(r.listIds.isEmpty() && r.itemIds.isEmpty())
    }

    @Test fun itemsOfAListThatWasNotReadAreNotJudged() {
        val r = removals(
            known = setOf("L", "lait"),
            lists = listOf("L"),
            items = listOf(LocalItemRef("lait", "L")),
            remoteLists = setOf("L"),
            remoteItems = emptyMap(),
        )
        assertTrue(r.itemIds.isEmpty())
    }
}
