package com.kaddie.app.ui.lists

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaddie.app.data.auth.User
import com.kaddie.app.data.settings.AppSettings
import com.kaddie.app.data.settings.ThemeMode
import com.kaddie.app.domain.model.ShoppingListSummary
import com.kaddie.app.domain.sync.SyncUiState
import com.kaddie.app.ui.components.SyncIndicator

@Composable
fun ListsScreen(
    viewModel: ListsViewModel,
    settings: AppSettings,
    syncState: SyncUiState,
    onSyncNow: () -> Unit,
    user: User?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onOpenList: (String) -> Unit,
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ShoppingListSummary?>(null) }
    var deleting by remember { mutableStateOf<ShoppingListSummary?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Kaddie", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    SyncIndicator(syncState, onSyncNow)
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Apparence")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nouvelle liste") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        if (user == null) SignInBanner(onSignIn)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val data = summaries) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> if (data.isEmpty()) {
                    EmptyLists(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(data, key = { it.list.id }) { summary ->
                            ListCard(
                                summary = summary,
                                onClick = { onOpenList(summary.list.id) },
                                onRename = { renaming = summary },
                                onDuplicate = { viewModel.duplicate(summary.list.id) },
                                onDelete = { deleting = summary },
                            )
                        }
                    }
                }
            }
        }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "Nouvelle liste",
            confirmLabel = "Créer",
            initialName = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                viewModel.createList(name, onOpenList)
            },
        )
    }
    renaming?.let { target ->
        NameDialog(
            title = "Renommer la liste",
            confirmLabel = "Renommer",
            initialName = target.list.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                viewModel.rename(target.list.id, name)
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer la liste ?") },
            text = { Text("« ${target.list.name} » et ses ${target.totalCount} produit(s) seront définitivement supprimés.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(target.list.id)
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
    if (showSettings) {
        SettingsDialog(settings, user, onSignIn, onSignOut, onThemeMode, onDynamicColor, onDismiss = { showSettings = false })
    }
}

@Composable
private fun EmptyLists(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🛒", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text("Aucune liste pour le moment", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Créez votre première liste de courses pour commencer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListCard(
    summary: ShoppingListSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val done = summary.totalCount - summary.remainingCount
    val progress = if (summary.totalCount == 0) 0f else done.toFloat() / summary.totalCount
    val status = when {
        summary.totalCount == 0 -> "Liste vide"
        summary.remainingCount == 0 -> "Tout est acheté"
        else -> "${summary.remainingCount} à acheter sur ${summary.totalCount}"
    }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 16.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (summary.totalCount > 0 && summary.remainingCount == 0) "✅" else "🛒",
                        fontSize = 24.sp,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.list.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions sur ${summary.list.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Renommer") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Dupliquer") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            if (summary.totalCount > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .semantics { contentDescription = "$done produits achetés sur ${summary.totalCount}" },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Nom de la liste") },
                placeholder = { Text("Courses de la semaine") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connectez-vous pour sauvegarder vos listes et les retrouver partout.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignIn) { Text("Se connecter") }
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: AppSettings,
    user: User?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Réglages") },
        text = {
            Column {
                Text("Compte", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (user == null) {
                    Text(
                        "Non connecté : vos listes restent sur cet appareil.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = { onDismiss(); onSignIn() }) { Text("Se connecter avec Google") }
                } else {
                    Text(
                        user.email ?: user.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Vos listes sont synchronisées. Se déconnecter conserve les données de cet appareil.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onDismiss(); onSignOut() }) { Text("Se déconnecter") }
                }
                Text("Apparence", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                listOf(ThemeMode.SYSTEM to "Système", ThemeMode.LIGHT to "Clair", ThemeMode.DARK to "Sombre").forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onThemeMode(mode) }.height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.themeMode == mode, onClick = { onThemeMode(mode) })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Couleurs dynamiques (Android 12+)", Modifier.weight(1f))
                    Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicColor)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
