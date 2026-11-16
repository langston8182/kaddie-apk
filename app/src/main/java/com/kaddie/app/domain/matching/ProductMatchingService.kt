package com.kaddie.app.domain.matching

import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ProductWithAliases
import com.kaddie.app.domain.repository.ProductDictionaryRepository
import com.kaddie.app.domain.text.EditDistance
import com.kaddie.app.domain.text.TextNormalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class MatchType { EXACT, PREFIX, ALIAS, WORD_PREFIX, FUZZY }

data class Suggestion(val product: Product, val matchType: MatchType)

sealed interface Resolution {
    /** Name or known alias identifies this product with certainty. */
    data class Exact(val product: Product) : Resolution

    /** Probable typo: never merged automatically, the user must confirm one of the candidates. */
    data class Uncertain(val candidates: List<Product>) : Resolution

    data object NotFound : Resolution
}

/**
 * Local, network-free product search over the dictionary. The index lives in memory (a few
 * thousand rows at most) and is backed by the persisted, indexed dictionary tables.
 */
class ProductMatchingService(
    private val dictionary: ProductDictionaryRepository,
    private val awaitReady: suspend () -> Unit = {},
) {
    private class Entry(val data: ProductWithAliases) {
        val product = data.product
        val compact = TextNormalizer.compact(product.key)
        val words = product.key.split(' ')
        val aliases = data.aliasKeys.map { it to TextNormalizer.compact(it) }
    }

    private val mutex = Mutex()
    private var index: List<Entry>? = null

    fun invalidate() {
        index = null
    }

    private suspend fun entries(): List<Entry> {
        index?.let { return it }
        awaitReady()
        return mutex.withLock {
            index ?: dictionary.loadAll().map(::Entry).also { index = it }
        }
    }

    suspend fun suggest(query: String, limit: Int = MAX_SUGGESTIONS): List<Suggestion> {
        val qn = TextNormalizer.normalize(query)
        if (qn.length < MIN_QUERY_LENGTH) return emptyList()
        val qk = TextNormalizer.key(query)
        val qc = TextNormalizer.compact(qk)
        val tolerance = tolerance(qc.length)

        class Scored(val suggestion: Suggestion, val distance: Int, val entry: Entry)

        val scored = entries().mapNotNull { e ->
            val p = e.product
            val (type, distance) = when {
                qk == p.key || qc == e.compact -> MatchType.EXACT to 0
                p.key.startsWith(qk) || p.normalized.startsWith(qn) || e.compact.startsWith(qc) -> MatchType.PREFIX to 0
                e.aliases.any { (k, c) -> k == qk || c == qc || k.startsWith(qk) || c.startsWith(qc) } -> MatchType.ALIAS to 0
                qk.length >= MIN_QUERY_LENGTH && !qk.contains(' ') && e.words.any { it.startsWith(qk) } -> MatchType.WORD_PREFIX to 0
                tolerance > 0 -> {
                    val d = fuzzyDistance(qk, qc, e)
                    if (d != null && d <= tolerance) MatchType.FUZZY to d else return@mapNotNull null
                }
                else -> return@mapNotNull null
            }
            Scored(Suggestion(p, type), distance, e)
        }

        return scored
            .sortedWith(
                compareBy<Scored> { it.suggestion.matchType.ordinal }
                    .thenBy { it.distance }
                    .thenByDescending { it.entry.product.usageCount }
                    .thenBy { it.entry.product.key.length }
                    .thenBy { it.entry.product.key },
            )
            .take(limit)
            .map { it.suggestion }
    }

    /** Resolves a free-typed name: exact/alias match, probable typo, or unknown. */
    suspend fun resolve(input: String): Resolution {
        val qk = TextNormalizer.key(input)
        if (qk.isEmpty()) return Resolution.NotFound
        val qc = TextNormalizer.compact(qk)
        val all = entries()

        val exact = all.filter { it.product.key == qk || it.compact == qc }
            .ifEmpty { all.filter { e -> e.aliases.any { (k, c) -> k == qk || c == qc } } }
        if (exact.isNotEmpty()) {
            return Resolution.Exact(exact.maxBy { it.product.usageCount }.product)
        }

        val tolerance = tolerance(qc.length)
        if (tolerance == 0) return Resolution.NotFound
        val close = all.mapNotNull { e ->
            val d = fullDistance(qk, qc, e)
            if (d <= tolerance) e to d else null
        }
        if (close.isEmpty()) return Resolution.NotFound
        val best = close.minOf { it.second }
        val candidates = close.filter { it.second == best }
            .sortedByDescending { it.first.product.usageCount }
            .take(MAX_CORRECTION_CANDIDATES)
            .map { it.first.product }
        return Resolution.Uncertain(candidates)
    }

    suspend fun getById(productId: String): Product? =
        entries().firstOrNull { it.product.id == productId }?.product

    /** Categories by product id, so list rows get the same pictogram fallback as the suggestions. */
    suspend fun categories(): Map<String, ProductCategory> = entries().associate { it.product.id to it.product.category }

    /** Creates a user product, or returns the existing one if the key is already known (no duplicates). */
    suspend fun createProduct(name: String, category: ProductCategory = ProductCategory.OTHER): Product {
        val display = TextNormalizer.displayName(name)
        val key = TextNormalizer.key(display)
        require(key.isNotEmpty()) { "Empty product name" }
        dictionary.findByKey(key)?.let { return it }
        val product = Product(
            id = UUID.randomUUID().toString(),
            name = display,
            normalized = TextNormalizer.normalize(display),
            key = key,
            category = category,
            isUserDefined = true,
            usageCount = 0,
        )
        dictionary.insert(product, emptyList())
        invalidate()
        return product
    }

    suspend fun recordUsage(productId: String) {
        dictionary.incrementUsage(productId)
        invalidate()
    }

    private fun fuzzyDistance(qk: String, qc: String, e: Entry): Int? {
        val candidates = buildList {
            add(EditDistance.damerauLevenshtein(qk, e.product.key))
            add(EditDistance.damerauLevenshtein(qc, e.compact))
            if (e.compact.length > qc.length) {
                add(EditDistance.damerauLevenshtein(qc, e.compact.take(qc.length)))
            }
            e.aliases.forEach { (_, c) -> add(EditDistance.damerauLevenshtein(qc, c)) }
        }
        return candidates.minOrNull()
    }

    private fun fullDistance(qk: String, qc: String, e: Entry): Int {
        var best = minOf(
            EditDistance.damerauLevenshtein(qk, e.product.key),
            EditDistance.damerauLevenshtein(qc, e.compact),
        )
        e.aliases.forEach { (_, c) -> best = minOf(best, EditDistance.damerauLevenshtein(qc, c)) }
        return best
    }

    /** Short words tolerate no typo, to keep truly distinct products (riz / rat) apart. */
    private fun tolerance(length: Int): Int = when {
        length >= 9 -> 2
        length >= 5 -> 1
        else -> 0
    }

    companion object {
        const val MIN_QUERY_LENGTH = 3
        const val MAX_SUGGESTIONS = 5
        const val MAX_CORRECTION_CANDIDATES = 3
    }
}
