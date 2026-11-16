package com.kaddie.app.ui.components

import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.text.TextNormalizer

/** Emoji pictograms for products, resolved from keywords in the name (most specific first). */
object ProductIcons {
    private val keywords: List<Pair<String, String>> = listOf(
        "pomme de terre" to "🥔", "patate" to "🥠", "tomate" to "🍅", "carotte" to "🥕", "oignon" to "🧅", "ail" to "🧄",
        "poivron" to "🫑", "piment" to "🌶️", "concombre" to "🥒", "courgette" to "🥒", "aubergine" to "🍆", "brocoli" to "🥦",
        "salade" to "🥬", "epinard" to "🥬", "mache" to "🥬", "chou" to "🥬", "mais" to "🌽", "champignon" to "🍄",
        "avocat" to "🥑", "citron" to "🍋", "orange" to "🍊", "clementine" to "🍊", "mandarine" to "🍊", "pomme" to "🍎",
        "poire" to "🍐", "banane" to "🍌", "fraise" to "🍓", "framboise" to "🍓", "myrtille" to "🫐", "cerise" to "🍒",
        "peche" to "🍑", "abricot" to "🍑", "raisin" to "🍇", "melon" to "🍈", "pasteque" to "🍉", "ananas" to "🍍",
        "mangue" to "🥭", "kiwi" to "🥝", "noix de coco" to "🥥", "olive" to "🫒", "haricot" to "🫘", "pois" to "🫛",
        "lentille" to "🫘", "persil" to "🌿", "basilic" to "🌿", "menthe" to "🌿", "coriandre" to "🌿", "ciboulette" to "🌿",
        "gingembre" to "🫚", "poulet" to "🍗", "dinde" to "🍗", "canard" to "🍗", "steak" to "🥩", "boeuf" to "🥩",
        "viande" to "🥩", "porc" to "🥩", "agneau" to "🥩", "veau" to "🥩", "jambon" to "🥓", "lardon" to "🥓", "lard" to "🥓",
        "saucisse" to "🌭", "saucisson" to "🌭", "merguez" to "🌭", "burger" to "🍔", "saumon" to "🐟", "thon" to "🐟",
        "poisson" to "🐟", "cabillaud" to "🐟", "sardine" to "🐟", "crevette" to "🦐", "gambas" to "🦐", "moule" to "🦪",
        "huitre" to "🦪", "crabe" to "🦀", "homard" to "🦞", "lait" to "🥛", "beurre" to "🧈", "creme" to "🥛",
        "yaourt" to "🥛", "fromage" to "🧀", "mozzarella" to "🧀", "emmental" to "🧀", "comte" to "🧀", "camembert" to "🧀",
        "oeuf" to "🥚", "pates" to "🍝", "spaghetti" to "🍝", "tagliatelle" to "🍝", "riz" to "🍚", "farine" to "🌾",
        "sucre" to "🍬", "sel" to "🧂", "poivre" to "🧂", "miel" to "🍯", "confiture" to "🍯", "huile" to "🫒",
        "flocon" to "🥣", "avoine" to "🥣", "muesli" to "🥣", "granola" to "🥣", "porridge" to "🥣",
        "vinaigre" to "🍶", "moutarde" to "🟡", "ketchup" to "🍅", "mayonnaise" to "🥫", "sauce" to "🥫", "conserve" to "🥫",
        "soupe" to "🍲", "chocolat" to "🍫", "biscuit" to "🍪", "cookie" to "🍪", "gateau" to "🍰", "glace" to "🍨",
        "sorbet" to "🍧", "bonbon" to "🍬", "cereale" to "🥣", "chips" to "🥔", "cacahuete" to "🥜", "amande" to "🥜",
        "noix" to "🥜", "noisette" to "🌰", "cafe" to "☕", "the" to "🍵", "tisane" to "🍵", "infusion" to "🍵",
        "eau" to "💧", "jus" to "🧃", "coca" to "🥤", "soda" to "🥤", "limonade" to "🥤", "biere" to "🍺", "vin" to "🍷",
        "champagne" to "🍾", "cidre" to "🍺", "whisky" to "🥃", "rhum" to "🥃", "vodka" to "🥃", "pain" to "🍞",
        "baguette" to "🥖", "croissant" to "🥐", "brioche" to "🥐", "pizza" to "🍕", "sandwich" to "🥪", "wrap" to "🌯",
        "tortilla" to "🌯", "crepe" to "🥞", "gaufre" to "🧇", "frite" to "🍟", "papier toilette" to "🧻", "mouchoir" to "🤧",
        "essuie" to "🧻", "dentifrice" to "🪥", "brosse a dents" to "🪥", "shampoing" to "🧴", "gel douche" to "🧴",
        "savon" to "🧼", "deodorant" to "🧴", "creme solaire" to "🧴", "rasoir" to "🪒", "coton" to "🧴", "couche" to "👶",
        "lessive" to "🧺", "adoucissant" to "🧺", "vaisselle" to "🍽️", "eponge" to "🧽", "javel" to "🧴", "nettoyant" to "🧴",
        "poubelle" to "🗑️", "sac" to "🛍️", "pile" to "🔋", "ampoule" to "💡", "bougie" to "🕯️", "fleur" to "💐",
        "bouquet" to "💐", "croquette" to "🐾", "litiere" to "🐱", "chien" to "🐶", "chat" to "🐱", "cadeau" to "🎁",
        "ballon" to "🎈", "livre" to "📖", "stylo" to "🖊️", "cahier" to "📓", "colle" to "🧴", "peinture" to "🎨",
        "marteau" to "🔨", "tournevis" to "🪛", "vis" to "🔩",
    )

    private val categoryIcons = mapOf(
        ProductCategory.FRUITS_LEGUMES to "🥬",
        ProductCategory.VIANDES_POISSONS to "🥩",
        ProductCategory.PRODUITS_LAITIERS to "🧀",
        ProductCategory.EPICERIE to "🥫",
        ProductCategory.BOISSONS to "🥤",
        ProductCategory.SURGELES to "🧊",
        ProductCategory.BOULANGERIE to "🥖",
        ProductCategory.HYGIENE to "🧴",
        ProductCategory.ENTRETIEN to "🧽",
        ProductCategory.OTHER to "🛍️",
    )

    fun categoryIcon(category: ProductCategory): String = categoryIcons.getValue(category)

    fun forName(name: String, category: ProductCategory? = null): String {
        val words = " ${TextNormalizer.key(name)} "
        val normalized = " ${TextNormalizer.normalize(name)} "
        val match = keywords.firstOrNull { (word, _) ->
            val k = TextNormalizer.key(word)
            words.contains(" $k ") || normalized.contains(" $word ") ||
                (word.length >= 4 && words.contains(" $k"))
        }
        return match?.second ?: category?.let(::categoryIcon) ?: "🛒"
    }
}
