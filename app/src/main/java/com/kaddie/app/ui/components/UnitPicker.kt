package com.kaddie.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kaddie.app.domain.model.MeasureUnit

@Composable
fun UnitPicker(selected: MeasureUnit, onSelected: (MeasureUnit) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected.symbol, style = MaterialTheme.typography.titleSmall) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choisir l'unité") },
            shape = CircleShape,
            border = null,
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.height(44.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MeasureUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.symbol) },
                    onClick = {
                        onSelected(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}
