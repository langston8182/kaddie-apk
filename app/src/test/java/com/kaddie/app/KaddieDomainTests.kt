package com.kaddie.app

import com.kaddie.app.domain.matching.MatchType
import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.matching.Resolution
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ProductWithAliases
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.model.ShoppingList
import com.kaddie.app.domain.model.ShoppingListSummary
import com.kaddie.app.domain.repository.ProductDictionaryRepository
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.repository.TransactionRunner
import com.kaddie.app.domain.text.EditDistance
import com.kaddie.app.domain.text.TextNormalizer
import com.kaddie.app.domain.usecase.AddItemRequest
import com.kaddie.app.domain.usecase.AddItemResult
import com.kaddie.app.domain.usecase.AddShoppingListItemUseCase
import com.kaddie.app.domain.usecase.ItemRules
import com.kaddie.app.domain.usecase.Quantity
import com.kaddie.app.domain.usecase.QuantityMerger
import com.kaddie.app.domain.usecase.UnitConflictResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeDictionary(seed: List<Triple<String, ProductCategory, List<String>>>) : ProductDictionaryRepository {
    val products = seed.map { (name, category, aliases) ->
        val key = TextNormalizer.key(name)
        ProductWithAliases(
            Product("id-$key", name, TextNormalizer.normalize(name), key, category, false, 0),
            aliases.map(TextNormalizer::key),
        )
    }.toMutableList()

    override suspend fun loadAll() = products.toList()
    override suspend fun findByKey(key: String) = products.firstOrNull { it.product.key == key }?.product
    override suspend fun insert(product: Product, aliasKeys: List<String>) {
        products += ProductWithAliases(product, aliasKeys)
    }
    override suspend fun deleteUserProduct(productId: String): com.kaddie.app.domain.repository.DeleteProductResult {
        val p = products.firstOrNull { it.product.id == productId } ?: return com.kaddie.app.domain.repository.DeleteProductResult.NOT_ALLOWED
        if (!p.product.isUserDefined) return com.kaddie.app.domain.repository.DeleteProductResult.NOT_ALLOWED
        if (usedProductIds.contains(productId)) return com.kaddie.app.domain.repository.DeleteProductResult.IN_USE
        products.remove(p)
        return com.kaddie.app.domain.repository.DeleteProductResult.DELETED
    }
    val usedProductIds = mutableSetOf<String>()
    override suspend fun incrementUsage(productId: String) {
        val i = products.indexOfFirst { it.product.id == productId }
        products[i] = products[i].copy(product = products[i].product.copy(usageCount = products[i].product.usageCount + 1))
    }
}

class FakeShoppingRepository : ShoppingListRepository {
    val items = mutableListOf<ShoppingItem>()
    override fun observeSummaries(): Flow<List<ShoppingListSummary>> = emptyFlow()
    override fun observeList(listId: String): Flow<ShoppingList?> = emptyFlow()
    override fun observeItems(listId: String): Flow<List<ShoppingItem>> = emptyFlow()
    override suspend fun createList(name: String) = ShoppingList("l", name, 0, 0)
    override suspend fun renameList(listId: String, name: String) = Unit
    override suspend fun deleteList(listId: String) = Unit
    override suspend fun duplicateList(listId: String): String? = null
    override suspend fun findItemByProduct(listId: String, productId: String) =
        items.firstOrNull { it.listId == listId && it.productId == productId }
    override suspend fun insertItem(item: ShoppingItem) { items += item }
    override suspend fun updateItem(item: ShoppingItem) {
        items[items.indexOfFirst { it.id == item.id }] = item
    }
    override suspend fun deleteItem(itemId: String) { items.removeAll { it.id == itemId } }
    override suspend fun getItem(itemId: String) = items.firstOrNull { it.id == itemId }
    override suspend fun touchList(listId: String) = Unit
    override suspend fun clearItems(listId: String) { items.removeAll { it.listId == listId } }
    override suspend fun clearPurchasedItems(listId: String) { items.removeAll { it.listId == listId && it.isPurchased } }
    override suspend fun uncheckAllItems(listId: String) {
        items.replaceAll { if (it.listId == listId) it.copy(isPurchased = false, purchasedQuantity = 0.0) else it }
    }
}

private val seed = listOf(
    Triple("Tomate", ProductCategory.FRUITS_LEGUMES, emptyList<String>()),
    Triple("Tomate cerise", ProductCategory.FRUITS_LEGUMES, emptyList()),
    Triple("Tomate pelée", ProductCategory.EPICERIE, emptyList()),
    Triple("Tomate concentrée", ProductCategory.EPICERIE, emptyList()),
    Triple("Carotte", ProductCategory.FRUITS_LEGUMES, emptyList()),
    Triple("Pomme de terre", ProductCategory.FRUITS_LEGUMES, listOf("patate")),
    Triple("Lait", ProductCategory.PRODUITS_LAITIERS, emptyList()),
    Triple("Pain", ProductCategory.BOULANGERIE, emptyList()),
    Triple("Riz", ProductCategory.EPICERIE, emptyList()),
    Triple("Rat", ProductCategory.OTHER, emptyList()),
)

class TextTests {
    @Test fun normalizeStripsAccentsCaseAndSeparators() {
        assertEquals("pomme de terre", TextNormalizer.normalize("  Pomme-de-TERRE "))
        assertEquals("creme fraiche", TextNormalizer.normalize("Crème   fraîche"))
    }

    @Test fun keyHandlesPluralsPrudently() {
        assertEquals(TextNormalizer.key("Tomate"), TextNormalizer.key("tomates"))
        assertEquals("gateau", TextNormalizer.key("gâteaux"))
        assertEquals("riz", TextNormalizer.key("riz"))
        assertEquals("pomme de terre", TextNormalizer.key("Pommes de terre"))
    }

    @Test fun damerauLevenshtein() {
        assertEquals(1, EditDistance.damerauLevenshtein("carote", "carotte"))
        assertEquals(1, EditDistance.damerauLevenshtein("tomtae", "tomate"))
        assertEquals(0, EditDistance.damerauLevenshtein("a", "a"))
    }
}

class MatchingTests {
    private lateinit var service: ProductMatchingService

    @Before fun setUp() {
        service = ProductMatchingService(FakeDictionary(seed))
    }

    @Test fun noSuggestionsBelowThreeCharacters() = runBlocking {
        assertTrue(service.suggest("to").isEmpty())
    }

    @Test fun suggestsTomatoFamilyWithExactFirst() = runBlocking {
        val names = service.suggest("tom").map { it.product.name }
        assertEquals(4, names.size)
        assertTrue(names.containsAll(listOf("Tomate", "Tomate cerise", "Tomate pelée", "Tomate concentrée")))
        assertEquals("Tomate", service.suggest("TOMATES").first().product.name)
        assertEquals(MatchType.EXACT, service.suggest("tomates").first().matchType)
    }

    @Test fun maxFiveSuggestions() = runBlocking {
        val many = FakeDictionary((1..10).map { Triple("Pomme $it", ProductCategory.OTHER, emptyList<String>()) })
        assertEquals(5, ProductMatchingService(many).suggest("pom").size)
    }

    @Test fun variantsResolveExactly() = runBlocking {
        for (input in listOf("tomate", "tomates", "TOMATE", "Tomate")) {
            assertEquals("Tomate", (service.resolve(input) as Resolution.Exact).product.name)
        }
        for (input in listOf("pomme-de-terre", "pomme de terre", "Pommes de terre", "pommedeterre", "patate")) {
            assertEquals("Pomme de terre", (service.resolve(input) as Resolution.Exact).product.name)
        }
    }

    @Test fun typoSuggestsCorrectionAndNeverMerges() = runBlocking {
        val r = service.resolve("carotes") as Resolution.Uncertain
        assertEquals("Carotte", r.candidates.single().name)
        assertEquals("Carotte", service.suggest("carotes").first().product.name)
    }

    @Test fun distinctProductsStayDistinct() = runBlocking {
        assertEquals("Tomate cerise", (service.resolve("tomate cerise") as Resolution.Exact).product.name)
        assertTrue(service.resolve("ris") is Resolution.NotFound)
        assertEquals("Riz", (service.resolve("riz") as Resolution.Exact).product.name)
        assertEquals("Rat", (service.resolve("rat") as Resolution.Exact).product.name)
    }

    @Test fun aBuiltInAliasBeatsAUserProductWithTheSameName() = runBlocking {
        val dict = FakeDictionary(listOf(Triple("Yaourt nature", ProductCategory.PRODUITS_LAITIERS, listOf("yaourt"))))
        val s = ProductMatchingService(dict)
        s.createProduct("Yaourt") // e.g. created by a sync from a product unknown here
        assertEquals("Yaourt nature", (s.resolve("yaourt") as Resolution.Exact).product.name)
    }

    @Test fun aWronglyCreatedProductCanBeDeletedAndStopsBeingSuggested() = runBlocking {
        val dict = FakeDictionary(seed)
        val s = ProductMatchingService(dict)
        val wrong = s.createProduct("Je veux trois avocats")
        assertEquals("Je veux trois avocats", s.suggest("je v").first().product.name)

        dict.usedProductIds += wrong.id
        assertEquals(com.kaddie.app.domain.repository.DeleteProductResult.IN_USE, s.deleteProduct(wrong.id))
        assertEquals(1, s.suggest("je v").size) // still used by a list: kept

        dict.usedProductIds.clear()
        assertEquals(com.kaddie.app.domain.repository.DeleteProductResult.DELETED, s.deleteProduct(wrong.id))
        assertTrue(s.suggest("je v").isEmpty())
        assertEquals(com.kaddie.app.domain.repository.DeleteProductResult.NOT_ALLOWED, s.deleteProduct("id-tomate")) // built-in products are protected
    }

    @Test fun unknownIsNotFoundAndCreationDeduplicates() = runBlocking {
        assertTrue(service.resolve("quinoa royal") is Resolution.NotFound)
        val created = service.createProduct("  quinoa  royal ")
        assertEquals("Quinoa royal", created.name)
        assertTrue(created.isUserDefined)
        assertEquals(created.id, service.createProduct("Quinoas royal").id)
        assertEquals(created.id, (service.resolve("quinoa royal") as Resolution.Exact).product.id)
    }

    @Test fun frequentProductsRankFirstWithinSameTier() = runBlocking {
        val dict = FakeDictionary(seed)
        val s = ProductMatchingService(dict)
        s.recordUsage("id-tomate cerise")
        s.recordUsage("id-tomate cerise")
        assertEquals("Tomate cerise", s.suggest("tom").first().product.name)
    }
}

class QuantityTests {
    @Test fun sameUnitAdds() {
        assertEquals(Quantity(5.0, MeasureUnit.PIECE), QuantityMerger.add(Quantity(2.0, MeasureUnit.PIECE), Quantity(3.0, MeasureUnit.PIECE)))
        assertEquals(Quantity(3.0, MeasureUnit.L), QuantityMerger.add(Quantity(1.0, MeasureUnit.L), Quantity(2.0, MeasureUnit.L)))
    }

    @Test fun compatibleUnitsConvert() {
        assertEquals(Quantity(1.5, MeasureUnit.KG), QuantityMerger.add(Quantity(500.0, MeasureUnit.G), Quantity(1.0, MeasureUnit.KG)))
        assertEquals(Quantity(1.5, MeasureUnit.L), QuantityMerger.add(Quantity(1.0, MeasureUnit.L), Quantity(500.0, MeasureUnit.ML)))
    }

    @Test fun incompatibleUnitsReturnNull() {
        assertNull(QuantityMerger.add(Quantity(1.0, MeasureUnit.KG), Quantity(1.0, MeasureUnit.PIECE)))
        assertNull(QuantityMerger.add(Quantity(1.0, MeasureUnit.PIECE), Quantity(1.0, MeasureUnit.PACK)))
    }
}

class AddItemUseCaseTests {
    private lateinit var repo: FakeShoppingRepository
    private lateinit var matching: ProductMatchingService
    private lateinit var useCase: AddShoppingListItemUseCase

    @Before fun setUp() {
        repo = FakeShoppingRepository()
        matching = ProductMatchingService(FakeDictionary(seed))
        val tx = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        useCase = AddShoppingListItemUseCase(repo, matching, tx) { 1_000L }
    }

    private fun req(name: String, q: Double, u: MeasureUnit = MeasureUnit.PIECE, extra: (AddItemRequest) -> AddItemRequest = { it }) =
        extra(AddItemRequest("l", name, q, u))

    @Test fun mergesSingularAndPlural() = runBlocking {
        assertTrue(useCase(req("Tomate", 2.0)) is AddItemResult.Added)
        val merged = useCase(req("Tomates", 3.0)) as AddItemResult.Merged
        assertEquals(5.0, merged.item.quantity, 0.0)
        assertEquals(1, repo.items.size)
    }

    @Test fun mergesCompatibleUnits() = runBlocking {
        useCase(req("Lait", 500.0, MeasureUnit.ML))
        val merged = useCase(req("Lait", 1.0, MeasureUnit.L)) as AddItemResult.Merged
        assertEquals(1.5, merged.item.quantity, 0.0)
        assertEquals(MeasureUnit.L, merged.item.unit)
    }

    @Test fun typoNeedsConfirmationThenMerges() = runBlocking {
        useCase(req("Carotte", 500.0, MeasureUnit.G))
        val needs = useCase(req("Carotes", 300.0, MeasureUnit.G)) as AddItemResult.NeedsCorrection
        assertEquals(1, repo.items.size)
        val merged = useCase(needs.request.copy(productId = needs.candidates.first().id)) as AddItemResult.Merged
        assertEquals(800.0, merged.item.quantity, 0.0)
        assertEquals(1, repo.items.size)
    }

    @Test fun declinedCorrectionCreatesNewProduct() = runBlocking {
        val needs = useCase(req("Carotes", 1.0)) as AddItemResult.NeedsCorrection
        val added = useCase(needs.request.copy(createAsNew = true)) as AddItemResult.Added
        assertEquals("Carotes", added.item.name)
    }

    @Test fun incompatibleUnitsAskThenApply() = runBlocking {
        useCase(req("Pain", 1.0))
        val needs = useCase(req("Pain", 200.0, MeasureUnit.G)) as AddItemResult.NeedsUnitDecision
        val replaced = useCase(needs.request.copy(unitConflict = UnitConflictResolution.REPLACE)) as AddItemResult.Merged
        assertEquals(MeasureUnit.G, replaced.item.unit)
        assertEquals(200.0, replaced.item.quantity, 0.0)
        val kept = useCase(req("Pain", 1.0) { it.copy(unitConflict = UnitConflictResolution.KEEP_EXISTING) })
        assertTrue(kept is AddItemResult.Unchanged)
    }

    @Test fun addingPurchasedItemPutsItBackToBuyKeepingBoughtQuantity() = runBlocking {
        val added = useCase(req("Pain", 1.0)) as AddItemResult.Added
        repo.updateItem(ItemRules.withPurchased(added.item, true, 2_000L))
        val merged = useCase(req("Pain", 1.0)) as AddItemResult.Merged
        assertEquals(2.0, merged.item.quantity, 0.0)
        assertEquals(1.0, merged.item.purchasedQuantity, 0.0)
        assertEquals(false, merged.item.isPurchased)
        assertTrue(merged.item.isPartiallyPurchased)
    }

    @Test fun unknownProductIsCreatedInDictionary() = runBlocking {
        val added = useCase(req("Kombucha gingembre", 1.0)) as AddItemResult.Added
        assertNotNull(matching.getById(added.item.productId))
    }

    @Test fun invalidInputsAreRejected() = runBlocking {
        assertTrue(useCase(req("Pain", 0.0)) is AddItemResult.Invalid)
        assertTrue(useCase(req("  ", 1.0)) is AddItemResult.Invalid)
    }

    @Test fun editKeepsPurchasedConsistent() {
        val base = ShoppingItem("i", "l", "p", "Pain", 2.0, MeasureUnit.PIECE, 2.0, true, "", 0, 0)
        val raised = ItemRules.withEdit(base, 3.0, MeasureUnit.PIECE, "", 5)
        assertEquals(false, raised.isPurchased)
        assertEquals(2.0, raised.purchasedQuantity, 0.0)
        val lowered = ItemRules.withEdit(base, 1.0, MeasureUnit.PIECE, "", 5)
        assertEquals(true, lowered.isPurchased)
    }
}

class GroupingAndHabitualTests {
    private fun item(id: String, productId: String) =
        ShoppingItem(id, "l", productId, productId, 1.0, MeasureUnit.PIECE, 0.0, false, "", 0, 0)

    @Test fun groupsByAisleInFixedOrderKeepingItemOrder() {
        val categories = mapOf(
            "lait" to ProductCategory.PRODUITS_LAITIERS,
            "pomme" to ProductCategory.FRUITS_LEGUMES,
            "tomate" to ProductCategory.FRUITS_LEGUMES,
            "eau" to ProductCategory.BOISSONS,
        )
        val groups = com.kaddie.app.domain.usecase.ItemGrouping.byCategory(
            listOf(item("1", "eau"), item("2", "lait"), item("3", "tomate"), item("4", "pomme"), item("5", "inconnu")),
            categories,
        )
        assertEquals(
            listOf(ProductCategory.FRUITS_LEGUMES, ProductCategory.PRODUITS_LAITIERS, ProductCategory.BOISSONS, ProductCategory.OTHER),
            groups.map { it.category },
        )
        assertEquals(listOf("3", "4"), groups.first().items.map { it.id })
        assertEquals(listOf("5"), groups.last().items.map { it.id })
    }

    @Test fun noItemsMeansNoGroups() {
        assertTrue(com.kaddie.app.domain.usecase.ItemGrouping.byCategory(emptyList(), emptyMap()).isEmpty())
    }

    @Test fun frequentProductsAreOrderedByUsageAndExcludeUnusedAndInList() = runBlocking {
        val service = ProductMatchingService(FakeDictionary(seed))
        assertTrue(service.frequent(5).isEmpty()) // nothing used yet
        repeat(3) { service.recordUsage("id-lait") }
        repeat(2) { service.recordUsage("id-pain") }
        service.recordUsage("id-carotte")

        assertEquals(listOf("Lait", "Pain", "Carotte"), service.frequent(5).map { it.name })
        assertEquals(listOf("Lait", "Pain"), service.frequent(2).map { it.name })
        assertEquals(listOf("Pain", "Carotte"), service.frequent(5, setOf("id-lait")).map { it.name })
    }
}

class ShareTextTests {
    private fun item(id: String, productId: String, name: String, q: Double, unit: MeasureUnit = MeasureUnit.PIECE,
                     bought: Double = 0.0, purchased: Boolean = false, note: String = "") =
        ShoppingItem(id, "l", productId, name, q, unit, bought, purchased, note, 0, 0)

    private val categories = mapOf("tomate" to ProductCategory.FRUITS_LEGUMES, "lait" to ProductCategory.PRODUITS_LAITIERS)

    @Test fun groupedByAisleSkippingBoughtItems() {
        val text = com.kaddie.app.ui.components.ShareText.format(
            "Semaine",
            listOf(
                item("1", "lait", "Lait", 2.0, MeasureUnit.L, note = "bio"),
                item("2", "tomate", "Tomate", 3.0),
                item("3", "pain", "Pain", 1.0, bought = 1.0, purchased = true),
            ),
            categories,
            groupByCategory = true,
        )
        assertEquals("🛒 Semaine\n\n🥬 Fruits et légumes\n• Tomate : 3 pièces\n\n🧀 Produits laitiers\n• Lait : 2 L (bio)", text)
    }

    @Test fun flatListAndPartialPurchaseShowsWhatRemains() {
        val text = com.kaddie.app.ui.components.ShareText.format(
            "Liste",
            listOf(item("1", "oeuf", "Oeufs", 6.0, bought = 2.0)),
            emptyMap(),
            groupByCategory = false,
        )
        assertEquals("🛒 Liste\n\n• Oeufs : 4 pièces", text)
    }

    @Test fun everythingBoughtSaysSo() {
        val text = com.kaddie.app.ui.components.ShareText.format(
            "Liste", listOf(item("1", "pain", "Pain", 1.0, bought = 1.0, purchased = true)), emptyMap(), true,
        )
        assertEquals("🛒 Liste\n\nTout est acheté !", text)
    }
}

class VoiceParserTests {
    private fun parse(text: String) = com.kaddie.app.domain.voice.VoiceParser.parse(text).map { Triple(it.name, it.quantity, it.unit) }

    @Test fun quantityUnitAndName() {
        assertEquals(listOf(Triple("Tomates", 2.0, MeasureUnit.KG)), parse("deux kilos de tomates"))
        assertEquals(listOf(Triple("Farine", 500.0, MeasureUnit.G)), parse("500 grammes de farine"))
        assertEquals(listOf(Triple("Farine", 500.0, MeasureUnit.G)), parse("500g de farine"))
        assertEquals(listOf(Triple("Lait", 1.5, MeasureUnit.L)), parse("1,5 litre de lait"))
        assertEquals(listOf(Triple("Oeufs", 12.0, MeasureUnit.PIECE)), parse("une douzaine d'oeufs"))
        assertEquals(listOf(Triple("Vin rouge", 3.0, MeasureUnit.BOTTLE)), parse("trois bouteilles de vin rouge"))
        assertEquals(listOf(Triple("Coca", 330.0, MeasureUnit.ML)), parse("33 cl de coca"))
    }

    @Test fun defaultsToOnePieceAndStripsArticles() {
        assertEquals(listOf(Triple("Lait", 1.0, MeasureUnit.PIECE)), parse("du lait"))
        assertEquals(listOf(Triple("Pain", 1.0, MeasureUnit.PIECE)), parse("un pain"))
        assertEquals(listOf(Triple("Pommes", 3.0, MeasureUnit.PIECE)), parse("3 pommes"))
        assertEquals(listOf(Triple("Pomme de terre", 1.0, MeasureUnit.PIECE)), parse("pomme de terre"))
    }

    @Test fun severalProductsInOneSentence() {
        assertEquals(
            listOf(
                Triple("Lait", 1.0, MeasureUnit.PIECE),
                Triple("Tomates", 2.0, MeasureUnit.KG),
                Triple("Farine", 500.0, MeasureUnit.G),
            ),
            parse("du lait, deux kilos de tomates et 500 g de farine"),
        )
    }

    @Test fun ignoresNoise() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("  ,  et ").isEmpty())
        assertTrue(parse("deux kilos de").isEmpty())
    }
}

class PinningTests {
    private val item = ShoppingItem("i", "l", "p", "Lait", 2.0, MeasureUnit.L, 0.0, false, "", 0, 0)

    @Test fun pinningOnlyChangesThePin() {
        val pinned = ItemRules.withPinned(item, true, 9)
        assertEquals(true, pinned.isPinned)
        assertEquals(2.0, pinned.quantity, 0.0)
        assertEquals(false, ItemRules.withPinned(pinned, false, 10).isPinned)
    }

    @Test fun editKeepsOrChangesThePin() {
        val pinned = item.copy(isPinned = true)
        assertEquals(true, ItemRules.withEdit(pinned, 3.0, MeasureUnit.L, "", 5).isPinned)
        assertEquals(false, ItemRules.withEdit(pinned, 3.0, MeasureUnit.L, "", 5, pinned = false).isPinned)
    }
}
