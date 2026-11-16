package com.kaddie.app.domain.usecase

import com.kaddie.app.domain.model.MeasureUnit
import kotlin.math.round

data class Quantity(val amount: Double, val unit: MeasureUnit)

object QuantityMerger {
    private const val EPSILON = 1e-6

    fun round3(value: Double): Double = round(value * 1000.0) / 1000.0

    fun convert(amount: Double, from: MeasureUnit, to: MeasureUnit): Double =
        if (from == to) amount else round3(amount * from.factor / to.factor)

    /**
     * Adds two quantities. Same unit: kept as is. Compatible units: converted and expressed in the
     * larger of the two units (500 g + 1 kg = 1.5 kg). Returns null when units are incompatible.
     */
    fun add(a: Quantity, b: Quantity): Quantity? {
        if (a.unit == b.unit) return Quantity(round3(a.amount + b.amount), a.unit)
        if (!a.unit.isCompatibleWith(b.unit)) return null
        val target = if (a.unit.factor >= b.unit.factor) a.unit else b.unit
        return Quantity(
            round3(convert(a.amount, a.unit, target) + convert(b.amount, b.unit, target)),
            target,
        )
    }

    fun isFullyPurchased(quantity: Double, purchased: Double): Boolean = purchased + EPSILON >= quantity
}
