package com.kaddie.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.ui.lists.TemplatesState

/** Lets the user tick one or several templates whose products are added to the current list. */
@Composable
internal fun ImportTemplatesDialog(
    templates: TemplatesState,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<ListTemplate>) -> Unit,
) {
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val loaded = (templates as? TemplatesState.Loaded)?.templates.orEmpty()
    val selected = loaded.filter { it.id in selectedIds }
    LaunchedEffect(Unit) { onLoad() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importer un modèle") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                when {
                    templates is TemplatesState.Failed -> {
                        Text(templates.message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onLoad) { Text("Réessayer") }
                    }
                    templates !is TemplatesState.Loaded -> Text("Chargement des modèles…", style = MaterialTheme.typography.bodyMedium)
                    loaded.isEmpty() -> Text(
                        "Aucun modèle pour le moment. Utilisez « Enregistrer comme modèle » depuis le menu d'une liste.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            "Leurs produits sont ajoutés à cette liste ; les quantités s'additionnent si un produit y est déjà.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        loaded.forEach { template ->
                            val isSelected = template.id in selectedIds
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { selectedIds = if (isSelected) selectedIds - template.id else selectedIds + template.id }
                                    .padding(start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                                Column {
                                    Text(template.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${template.itemCount} produit(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) { Text("Importer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
