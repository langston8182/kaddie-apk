package com.kaddie.app

import com.kaddie.app.data.repository.mergeTemplateItems
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.TemplateItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateMergeTests {
    private fun item(id: String, quantity: Double, unit: MeasureUnit = MeasureUnit.PIECE) =
        TemplateItem(id, id, quantity, unit, "")

    @Test
    fun keepsOrderAndSumsSameProductWithSameUnit() {
        val a = ListTemplate("a", "A", listOf(item("lait", 1.0), item("pain", 1.0)))
        val b = ListTemplate("b", "B", listOf(item("oeuf", 6.0), item("lait", 2.0)))
        val merged = mergeTemplateItems(listOf(a, b))
        assertEquals(listOf("lait", "pain", "oeuf"), merged.map { it.productId })
        assertEquals(3.0, merged.first().quantity, 0.0)
    }

    @Test
    fun keepsFirstWhenUnitsDiffer() {
        val a = ListTemplate("a", "A", listOf(item("farine", 500.0, MeasureUnit.G)))
        val b = ListTemplate("b", "B", listOf(item("farine", 1.0, MeasureUnit.KG)))
        val merged = mergeTemplateItems(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(500.0, merged.single().quantity, 0.0)
    }
}
