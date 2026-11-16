package com.kaddie.app.domain.model

enum class UnitDimension { PIECE, PACK, BOTTLE, MASS, VOLUME }

/** [factor] converts one unit into the base unit of its dimension (g for mass, mL for volume). */
enum class MeasureUnit(
    val symbol: String,
    val singular: String,
    val plural: String,
    val dimension: UnitDimension,
    val factor: Double,
) {
    PIECE("pièce", "pièce", "pièces", UnitDimension.PIECE, 1.0),
    PACK("paquet", "paquet", "paquets", UnitDimension.PACK, 1.0),
    BOTTLE("bouteille", "bouteille", "bouteilles", UnitDimension.BOTTLE, 1.0),
    KG("kg", "kg", "kg", UnitDimension.MASS, 1000.0),
    G("g", "g", "g", UnitDimension.MASS, 1.0),
    L("L", "L", "L", UnitDimension.VOLUME, 1000.0),
    ML("mL", "mL", "mL", UnitDimension.VOLUME, 1.0);

    fun isCompatibleWith(other: MeasureUnit) = dimension == other.dimension

    fun label(quantity: Double): String = if (quantity > 1.0) plural else singular

    companion object {
        fun fromCode(code: String): MeasureUnit = entries.firstOrNull { it.name == code } ?: PIECE
    }
}

enum class ProductCategory(val code: String, val label: String) {
    FRUITS_LEGUMES("fruits_legumes", "Fruits et légumes"),
    VIANDES_POISSONS("viandes_poissons", "Viandes et poissons"),
    PRODUITS_LAITIERS("produits_laitiers", "Produits laitiers"),
    EPICERIE("epicerie", "Épicerie"),
    BOISSONS("boissons", "Boissons"),
    SURGELES("surgeles", "Surgelés"),
    BOULANGERIE("boulangerie", "Boulangerie"),
    HYGIENE("hygiene", "Hygiène"),
    ENTRETIEN("entretien", "Entretien"),
    OTHER("autres", "Autres");

    companion object {
        fun fromCode(code: String): ProductCategory = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

data class ShoppingList(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ShoppingListSummary(
    val list: ShoppingList,
    val remainingCount: Int,
    val totalCount: Int,
)

data class Product(
    val id: String,
    val name: String,
    val normalized: String,
    val key: String,
    val category: ProductCategory,
    val isUserDefined: Boolean,
    val usageCount: Int,
)

data class ProductWithAliases(
    val product: Product,
    val aliasKeys: List<String>,
)

data class ShoppingItem(
    val id: String,
    val listId: String,
    val productId: String,
    val name: String,
    /** Total requested quantity. */
    val quantity: Double,
    val unit: MeasureUnit,
    /** Part of [quantity] already bought; the item is purchased once it reaches [quantity]. */
    val purchasedQuantity: Double,
    val isPurchased: Boolean,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val remainingQuantity: Double get() = (quantity - purchasedQuantity).coerceAtLeast(0.0)
    val isPartiallyPurchased: Boolean get() = !isPurchased && purchasedQuantity > 0.0
}
