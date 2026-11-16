package com.kaddie.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.kaddie.app.ui.components.ProductIcons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaddie.app.domain.model.MeasureUnit
import com.kaddie.app.domain.model.ProductCategory
import com.kaddie.app.domain.model.ShoppingItem
import com.kaddie.app.domain.sync.SyncUiState
import com.kaddie.app.ui.components.EnvironmentBadge
import com.kaddie.app.ui.components.ShareText
import com.kaddie.app.ui.components.SyncIndicator
import com.kaddie.app.domain.usecase.ItemGrouping
import com.kaddie.app.domain.usecase.UnitConflictResolution
import com.kaddie.app.ui.components.UnitPicker
import com.kaddie.app.ui.components.formatQuantity
import com.kaddie.app.ui.components.formatQuantityWithUnit
import com.kaddie.app.ui.components.parseQuantity
import com.kaddie.app.ui.components.quantityLabel

@Composable
fun ListDetailScreen(
    viewModel: ListDetailViewModel,
    syncState: SyncUiState,
    onSyncNow: () -> Unit,
    groupByCategory: Boolean,
    onGroupByCategory: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val listItems by viewModel.items.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) viewModel.addFromVoice(spoken)
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dites vos produits")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "La reconnaissance vocale n'est pas disponible sur cet appareil", Toast.LENGTH_LONG).show()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
    LaunchedEffect(header) {
        if (header is ListHeader.Missing) onBack()
    }

    val toBuy = listItems.orEmpty().filter { !it.isPurchased }
    val purchased = listItems.orEmpty().filter { it.isPurchased }
    val title = (header as? ListHeader.Loaded)?.name.orEmpty()

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        if (listItems != null) {
                            Text(
                                when {
                                    listItems.orEmpty().isEmpty() -> "Liste vide"
                                    toBuy.isEmpty() -> "Tout est acheté"
                                    else -> "${toBuy.size} à acheter"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    EnvironmentBadge()
                    SyncIndicator(syncState, onSyncNow)
                    if (!listItems.isNullOrEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Actions de la liste")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Partager la liste") },
                                    onClick = {
                                        menuOpen = false
                                        val text = ShareText.format(title, listItems.orEmpty(), categories, groupByCategory)
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, title)
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }
                                        context.startActivity(Intent.createChooser(send, "Partager la liste"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (groupByCategory) "Afficher sans rayons" else "Regrouper par rayon") },
                                    onClick = { menuOpen = false; onGroupByCategory(!groupByCategory) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Tout décocher (réutiliser la liste)") },
                                    enabled = purchased.isNotEmpty(),
                                    onClick = { menuOpen = false; viewModel.uncheckAll() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Retirer les produits achetés") },
                                    enabled = purchased.isNotEmpty(),
                                    onClick = { menuOpen = false; viewModel.clearPurchased() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Vider la liste") },
                                    onClick = { menuOpen = false; confirmClear = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = { AddBar(viewModel, onVoice = startVoice) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                listItems == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                listItems.orEmpty().isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🧺", fontSize = 56.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Votre liste est vide.\nAjoutez un produit ci-dessous.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                    val row: @Composable (ShoppingItem, Modifier) -> Unit = { item, modifier ->
                        ItemRow(
                            item = item,
                            category = categories[item.productId],
                            onToggle = { viewModel.setPurchased(item, !item.isPurchased) },
                            onEdit = { editing = item },
                            onTogglePin = { viewModel.togglePinned(item) },
                            onDelete = { viewModel.delete(item) },
                            modifier = modifier,
                        )
                    }
                    val pinnedItems = toBuy.filter { it.isPinned }
                    val rest = toBuy.filter { !it.isPinned }
                    if (pinnedItems.isNotEmpty()) {
                        item(key = "pinned-header") {
                            Text(
                                "📌  Épinglés (${pinnedItems.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).animateItem(),
                            )
                        }
                        items(pinnedItems, key = { it.id }) { item -> row(item, Modifier.animateItem()) }
                    }
                    if (groupByCategory) {
                        ItemGrouping.byCategory(rest, categories).forEach { group ->
                            item(key = "aisle-${group.category.code}") {
                                AisleHeader(group.category, group.items.size, Modifier.animateItem())
                            }
                            items(group.items, key = { it.id }) { item -> row(item, Modifier.animateItem()) }
                        }
                    } else {
                        items(rest, key = { it.id }) { item -> row(item, Modifier.animateItem()) }
                    }
                    if (purchased.isNotEmpty()) {
                        item(key = "purchased-header") {
                            Text(
                                "Dans le panier (${purchased.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).animateItem(),
                            )
                        }
                        items(purchased, key = { it.id }) { item -> row(item, Modifier.animateItem()) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vider la liste ?") },
            text = { Text("Les ${listItems.orEmpty().size} produits seront supprimés. La liste elle-même est conservée.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clearAll() }) {
                    Text("Vider", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }

    editing?.let { target ->
        EditItemDialog(
            item = target,
            onDismiss = { editing = null },
            onConfirm = { quantity, unit, note, pinned ->
                editing = null
                viewModel.edit(target, quantity, unit, note, pinned)
            },
        )
    }

    when (val p = prompt) {
        is Prompt.Correction -> AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("Vouliez-vous dire…") },
            text = {
                Column {
                    Text("« ${p.request.name} » ressemble à un produit connu.")
                    p.candidates.forEach { candidate ->
                        TextButton(onClick = { viewModel.resolveCorrection(p.request, candidate) }) {
                            Text(candidate.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveCorrection(p.request, null) }) {
                    Text("Garder « ${p.request.name} »")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPrompt) { Text("Annuler") } },
        )
        is Prompt.UnitConflict -> AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            title = { Text("Unités différentes") },
            text = {
                Text(
                    "${p.existing.name} est déjà dans la liste (${p.existing.quantityLabel()}). " +
                        "Impossible d'additionner avec ${formatQuantityWithUnit(p.request.quantity, p.request.unit)}.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveUnitConflict(p.request, UnitConflictResolution.REPLACE) }) {
                    Text("Remplacer par ${formatQuantityWithUnit(p.request.quantity, p.request.unit)}")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveUnitConflict(p.request, UnitConflictResolution.KEEP_EXISTING) }) {
                    Text("Garder l'existant")
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun AisleHeader(category: ProductCategory, count: Int, modifier: Modifier = Modifier) {
    Text(
        text = "${ProductIcons.categoryIcon(category)}  ${category.label} ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ItemRow(
    item: ShoppingItem,
    category: ProductCategory?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Supprimer ${item.name}",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        val muted = item.isPurchased
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClickLabel = "Modifier ${item.name}",
                    onClick = onEdit,
                    onLongClickLabel = if (item.isPinned) "Désépingler ${item.name}" else "Épingler ${item.name} en haut",
                    onLongClick = onTogglePin,
                )
                .heightIn(min = 64.dp)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.isPurchased,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = "${item.name}, acheté" },
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .alpha(if (muted) 0.55f else 1f)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(ProductIcons.forName(item.name, category), fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).alpha(if (muted) 0.55f else 1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (muted) TextDecoration.LineThrough else null,
                )
                val detail = buildString {
                    append(item.quantityLabel())
                    if (item.isPartiallyPurchased) {
                        append(" · reste ${formatQuantityWithUnit(item.remainingQuantity, item.unit)}")
                    }
                    if (item.note.isNotBlank()) append(" · ${item.note}")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = modifier)
}

@Composable
private fun AddBar(viewModel: ListDetailViewModel, onVoice: () -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val quantity by viewModel.quantityText.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val habitual by viewModel.habitual.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            AnimatedVisibility(
                visible = suggestions.isNotEmpty() || habitual.isNotEmpty(),
                enter = fadeIn(tween(120)) + expandVertically(tween(150)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(120)),
            ) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    if (suggestions.isEmpty()) {
                        Text(
                            "Vos habituels",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        )
                    }
                    val products = if (suggestions.isNotEmpty()) suggestions.map { it.product } else habitual
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(products, key = { it.id }) { product ->
                            SuggestionChip(
                                onClick = { viewModel.submit(product) },
                                shape = CircleShape,
                                border = null,
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                                label = { Text("${ProductIcons.forName(product.name, product.category)}  ${product.name}") },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Ajouter un produit…") },
                    leadingIcon = { Icon(Icons.Filled.AddShoppingCart, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = onVoice) {
                            Icon(Icons.Filled.Mic, contentDescription = "Ajouter par la voix", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    // Handling Done ourselves keeps the keyboard open for consecutive entries.
                    keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = { viewModel.submit() },
                    enabled = query.isNotBlank(),
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Ajouter à la liste", modifier = Modifier.size(28.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                QuantityStepper(
                    value = quantity,
                    onValueChange = viewModel::onQuantityChange,
                    onStep = viewModel::stepQuantity,
                    onDone = { viewModel.submit() },
                )
                Spacer(Modifier.width(10.dp))
                UnitPicker(selected = unit, onSelected = viewModel::onUnitChange)
            }
        }
    }
}

/** Compact − / quantity / + control; the number can also be typed (decimals allowed). */
@Composable
private fun QuantityStepper(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Diminuer la quantité")
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(52.dp).semantics { contentDescription = "Quantité" },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
        IconButton(onClick = { onStep(1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Augmenter la quantité")
        }
    }
}

@Composable
private fun EditItemDialog(
    item: ShoppingItem,
    onDismiss: () -> Unit,
    onConfirm: (Double, MeasureUnit, String, Boolean) -> Unit,
) {
    var pinned by remember { mutableStateOf(item.isPinned) }
    var quantityText by remember { mutableStateOf(formatQuantity(item.quantity)) }
    var unit by remember { mutableStateOf(item.unit) }
    var note by remember { mutableStateOf(item.note) }
    val quantity = parseQuantity(quantityText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(8) },
                        label = { Text("Quantité") },
                        isError = quantity == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    UnitPicker(selected = unit, onSelected = { unit = it })
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (facultatif)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📌  Épingler en haut de la liste", Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { quantity?.let { onConfirm(it, unit, note, pinned) } }, enabled = quantity != null) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
