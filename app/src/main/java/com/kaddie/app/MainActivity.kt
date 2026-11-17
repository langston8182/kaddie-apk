package com.kaddie.app

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kaddie.app.data.auth.SignInCancelledException
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaddie.app.ui.detail.ListDetailScreen
import com.kaddie.app.ui.detail.ListDetailViewModel
import com.kaddie.app.ui.templates.TemplatesScreen
import com.kaddie.app.ui.templates.TemplatesViewModel
import com.kaddie.app.ui.lists.ListsScreen
import com.kaddie.app.ui.lists.ListsViewModel
import com.kaddie.app.ui.theme.KaddieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KaddieApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            KaddieTheme(settings) {
                KaddieNavHost(container)
            }
        }
    }
}

@Composable
private fun KaddieNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val syncState by container.syncManager.uiState.collectAsStateWithLifecycle()
    val user by container.authRepository.currentUser.collectAsStateWithLifecycle()

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            container.authRepository.completeSignIn(result.data)
                .onSuccess { container.syncManager.syncNow() }
                .onFailure {
                    if (it !is SignInCancelledException) {
                        Toast.makeText(context, it.message ?: "Connexion impossible", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }
    val signIn = {
        try {
            signInLauncher.launch(container.authRepository.createSignInIntent())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Aucun navigateur disponible pour se connecter", Toast.LENGTH_LONG).show()
        }
    }
    val signOut = {
        scope.launch {
            container.authRepository.signOut()
            try {
                context.startActivity(container.authRepository.createSignOutIntent())
            } catch (e: ActivityNotFoundException) {
                // Signed out locally anyway; only the hosted-UI session cookie remains.
            }
        }
        Unit
    }
    // While signed out, the indicator doubles as a sign-in shortcut.
    val syncNow = { if (user == null) signIn() else container.syncManager.syncNow() }

    NavHost(navController = navController, startDestination = "lists") {
        composable("lists") {
            val vm: ListsViewModel = viewModel(
                factory = viewModelFactory { initializer { ListsViewModel(container.shoppingLists, container.templates) } },
            )
            ListsScreen(
                viewModel = vm,
                settings = settings,
                syncState = syncState,
                onSyncNow = syncNow,
                user = user,
                onSignIn = signIn,
                onSignOut = signOut,
                onThemeMode = container.settings::setThemeMode,
                onDynamicColor = container.settings::setDynamicColor,
                onOpenList = { id -> navController.navigate("list/$id") },
                onOpenTemplates = { navController.navigate("templates") },
            )
        }
        composable("templates") {
            val vm: TemplatesViewModel = viewModel(
                factory = viewModelFactory { initializer { TemplatesViewModel(container.templates, container.matching) } },
            )
            TemplatesScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(
            route = "list/{listId}",
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) { entry ->
            val listId = requireNotNull(entry.arguments?.getString("listId"))
            val vm: ListDetailViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ListDetailViewModel(listId, container.shoppingLists, container.matching, container.addItem, container.templates)
                    }
                },
            )
            ListDetailScreen(
                viewModel = vm,
                syncState = syncState,
                onSyncNow = syncNow,
                groupByCategory = settings.groupByCategory,
                onGroupByCategory = container.settings::setGroupByCategory,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
