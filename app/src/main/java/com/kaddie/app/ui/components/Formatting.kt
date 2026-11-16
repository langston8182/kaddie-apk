package com.kaddie.app.ui.components

import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.ShoppingItem
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val quantityFormat = DecimalFormat("#.###", DecimalFormatSymbols(Locale.FRANCE))

fun formatQuantity(quantity: Double): String = quantityFormat.format(quantity)

fun formatQuantityWithUnit(quantity: Double, unit: MeasureUnit): String =
    "${formatQuantity(quantity)} ${unit.label(quantity)}"

fun ShoppingItem.quantityLabel(): String = formatQuantityWithUnit(quantity, unit)

fun parseQuantity(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
