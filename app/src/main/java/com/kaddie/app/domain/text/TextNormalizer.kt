package com.kaddie.app.domain.text

import java.text.Normalizer

object TextNormalizer {
    private val diacritics = Regex("\\p{Mn}+")
    private val separators = Regex("[^a-z0-9]+")

    /** Lowercase, accent-free, with spaces/hyphens/apostrophes unified into single spaces. */
    fun normalize(input: String): String {
        val lowered = input.lowercase()
            .replace("œ", "oe")
            .replace("æ", "ae")
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return diacritics.replace(decomposed, "")
            .replace(separators, " ")
            .trim()
    }

    /** Normalized form where each word is reduced to a prudent singular, used to match plural variants. */
    fun key(input: String): String =
        normalize(input).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { singularize(it) }

    fun compact(key: String): String = key.replace(" ", "")

    /** Only strips a plural "s", or the "x" of -eaux / -oux, and never from very short words. */
    private fun singularize(word: String): String = when {
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        word.length > 4 && (word.endsWith("eaux") || word.endsWith("oux")) -> word.dropLast(1)
        else -> word
    }

    /** Display form of a user-typed product name: trimmed, single-spaced, first letter capitalized. */
    fun displayName(input: String): String {
        val cleaned = input.trim().replace(Regex("\\s+"), " ")
        return cleaned.replaceFirstChar { it.uppercase() }
    }
}
