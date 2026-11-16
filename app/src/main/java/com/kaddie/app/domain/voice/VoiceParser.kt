package com.kaddie.app.domain.voice

import com.kaddie.app.domain.model.MeasureUnit
import java.text.Normalizer

data class SpokenItem(val name: String, val quantity: Double, val unit: MeasureUnit)

/**
 * Turns a dictated French sentence into products: "deux kilos de tomates, du lait et 500 g de farine".
 * Quantity and unit are optional (default: 1 piece). Parsing is deliberately forgiving, because speech
 * recognizers write numbers either as digits or as words.
 */
object VoiceParser {
    // A comma between two digits is a decimal separator ("1,5 litre"), anything else separates products.
    private val separators = Regex("""\s*(?:(?<!\d),|,(?!\d)|;|\bet\b|\bpuis\b|\bainsi que\b)\s*""")
    private val numberWithUnit = Regex("""^(\d+(?:[.,]\d+)?)(kg|g|l|ml|cl)$""")
    private val digits = Regex("""^\d+(?:[.,]\d+)?$""")
    private val articles = setOf("de", "du", "des", "d", "la", "le", "les", "l", "un", "une")

    private val numberWords = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13,
        "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "trente" to 30, "quarante" to 40,
        "cinquante" to 50, "soixante" to 60, "cent" to 100,
    )

    /** unit -> (MeasureUnit, multiplier applied to the quantity) */
    private val units: Map<String, Pair<MeasureUnit, Double>> = buildMap {
        fun add(unit: MeasureUnit, factor: Double, vararg words: String) = words.forEach { put(it, unit to factor) }
        add(MeasureUnit.KG, 1.0, "kg", "kilo", "kilos", "kilogramme", "kilogrammes")
        add(MeasureUnit.G, 1.0, "g", "gramme", "grammes", "gr")
        add(MeasureUnit.L, 1.0, "l", "litre", "litres")
        add(MeasureUnit.ML, 1.0, "ml", "millilitre", "millilitres")
        add(MeasureUnit.ML, 10.0, "cl", "centilitre", "centilitres")
        add(MeasureUnit.BOTTLE, 1.0, "bouteille", "bouteilles")
        add(MeasureUnit.PACK, 1.0, "paquet", "paquets", "boite", "boites")
        add(MeasureUnit.PIECE, 1.0, "piece", "pieces", "unite", "unites")
        add(MeasureUnit.PIECE, 12.0, "douzaine", "douzaines")
    }

    fun parse(text: String): List<SpokenItem> =
        separators.split(text.trim().lowercase()).mapNotNull { parseSegment(it) }

    private fun parseSegment(segment: String): SpokenItem? {
        val tokens = segment.replace("'", " ").replace("’", " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null

        var quantity: Double? = null
        var unit: MeasureUnit? = null

        numberWithUnit.matchEntire(tokens.first())?.let { m ->
            val (u, factor) = units.getValue(m.groupValues[2])
            quantity = m.groupValues[1].replace(',', '.').toDouble() * factor
            unit = u
            tokens.removeAt(0)
        }
        if (quantity == null) {
            quantity = leadingNumber(tokens.first())
            if (quantity != null) tokens.removeAt(0)
        }
        if (unit == null) {
            val found = tokens.firstOrNull()?.let { units[plain(it)] }
            if (found != null && quantity != null) {
                unit = found.first
                quantity = quantity!! * found.second
                tokens.removeAt(0)
            }
        }
        // "une demi" / "un demi-litre" are not supported: keep the words in the name rather than guess.
        while (tokens.isNotEmpty() && plain(tokens.first()) in articles) tokens.removeAt(0)
        if (tokens.isEmpty()) return null

        val q = quantity ?: 1.0
        if (!(q > 0.0)) return null
        return SpokenItem(
            name = tokens.joinToString(" ").replaceFirstChar { it.uppercase() },
            quantity = Math.round(q * 1000) / 1000.0,
            unit = unit ?: MeasureUnit.PIECE,
        )
    }

    private fun leadingNumber(token: String): Double? =
        if (digits.matches(token)) token.replace(',', '.').toDoubleOrNull() else numberWords[plain(token)]?.toDouble()

    private fun plain(token: String): String =
        Normalizer.normalize(token, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
