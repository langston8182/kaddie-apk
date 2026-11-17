package com.kaddie.app.ui.templates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaddie.app.domain.model.ListTemplate
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.Product
import com.kaddie.app.domain.model.TemplateItem
import com.kaddie.app.ui.components.ProductIcons
import com.kaddie.app.ui.components.UnitPicker
import com.kaddie.app.ui.components.formatQuantity
import com.kaddie.app.ui.components.formatQuantityWithUnit
import com.kaddie.app.ui.components.parseQuantity
import com.kaddie.app.ui.lists.TemplatesState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(viewModel: TemplatesViewModel, onBack: () -> Unit) {
    val state by viewModel.templates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ListTemplate?>(null) }
    val templates = (state as? TemplatesState.Loaded)?.templates.orEmpty()
    val editing = templates.firstOrNull { it.id == editingId }

    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it) } }

    if (editing != null) {
        TemplateEditor(
            template = editing,
            viewModel = viewModel,
            snackbar = snackbar,
            onClose = { editingId = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modèles", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is TemplatesState.Idle, is TemplatesState.Loading ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                is TemplatesState.Failed -> Column(Modifier.padding(24.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = viewModel::refresh) { Text("Réessayer") }
                }
                is TemplatesState.Loaded -> if (s.templates.isEmpty()) {
                    Text(
                        "Aucun modèle pour le moment. Utilisez « Enregistrer comme modèle » depuis le menu d'une liste.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(s.templates, key = { it.id }) { template ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .clickable { editingId = template.id }
                                    .padding(start = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(template.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${template.itemCount} produit(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleting = template }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer le modèle ${template.name}")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer ce modèle ?") },
            text = { Text("« ${target.name} » sera supprimé. Les listes déjà créées ne changent pas.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; viewModel.delete(target.id) }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditor(
    template: ListTemplate,
    viewModel: TemplatesViewModel,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    var name by rememberSaveable(template.id) { mutableStateOf(template.name) }
    val items: SnapshotStateList<TemplateItem> = remember(template.id) { template.items.toMutableStateList() }
    var editingItem by remember { mutableStateOf<TemplateItem?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val dirty = name.trim() != template.name || items.toList() != template.items
    val canSave = dirty && name.isNotBlank() && items.isNotEmpty() && !saving
    val close = { if (dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier le modèle", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            saving = true
                            viewModel.save(template.copy(name = name.trim(), items = items.toList())) { onClose() }
                        },
                    ) { Text("Enregistrer") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Nom du modèle") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AddProductSection(
                    viewModel = viewModel,
                    onAdd = { product ->
                        if (items.none { it.productId == product.id }) {
                            items += TemplateItem(product.id, product.name, 1.0, MeasureUnit.PIECE, "")
                        }
                    },
                )
                Text(
                    "Produits (${items.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(items.toList(), key = { it.productId }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { editingItem = item }
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ProductIcons.forName(item.name), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatQuantityWithUnit(item.quantity, item.unit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { items.remove(item) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Retirer ${item.name}")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    editingItem?.let { target ->
        EditQuantityDialog(
            item = target,
            onDismiss = { editingItem = null },
            onConfirm = { quantity, unit ->
                val index = items.indexOfFirst { it.productId == target.productId }
                if (index >= 0) items[index] = target.copy(quantity = quantity, unit = unit)
                editingItem = null
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Abandonner les modifications ?") },
            text = { Text("Les changements non enregistrés seront perdus.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose() }) {
                    Text("Abandonner", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continuer") } },
        )
    }
}

@Composable
private fun AddProductSection(viewModel: TemplatesViewModel, onAdd: (Product) -> Unit) {
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val suggestions by produceState(emptyList<Product>(), query) { value = viewModel.suggest(query) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Ajouter un produit") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    if (query.isNotBlank()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(suggestions, key = { it.id }) { product ->
                SuggestionChip("${ProductIcons.forName(product.name, product.category)}  ${product.name}") {
                    onAdd(product)
                    query = ""
                }
            }
            if (suggestions.none { it.name.equals(query.trim(), ignoreCase = true) }) {
                item {
                    SuggestionChip("Créer « ${query.trim()} »") {
                        val typed = query
                        query = ""
                        scope.launch { onAdd(viewModel.productFor(typed)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.heightIn(min = 40.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun EditQuantityDialog(item: TemplateItem, onDismiss: () -> Unit, onConfirm: (Double, MeasureUnit) -> Unit) {
    var quantityText by remember { mutableStateOf(formatQuantity(item.quantity)) }
    var unit by remember { mutableStateOf(item.unit) }
    val quantity = parseQuantity(quantityText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantité") },
                    singleLine = true,
                    isError = quantity == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                UnitPicker(selected = unit, onSelected = { unit = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(quantity!!, unit) }, enabled = quantity != null && quantity > 0) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
