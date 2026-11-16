package com.kaddie.app

import android.app.Application
import android.content.Context
import com.kaddie.app.data.auth.AuthRepository
import com.kaddie.app.data.auth.CognitoAuthRepository
import com.kaddie.app.data.auth.CognitoTokenEndpoint
import com.kaddie.app.data.auth.KeystoreTokenStore
import com.kaddie.app.data.auth.TokenManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.kaddie.app.data.dictionary.DictionaryInitializer
import com.kaddie.app.data.local.KaddieDatabase
import com.kaddie.app.data.remote.KaddieApiFactory
import com.kaddie.app.data.remote.ShoppingRemoteDataSource
import com.kaddie.app.data.repository.RoomProductDictionaryRepository
import com.kaddie.app.data.repository.RoomShoppingListRepository
import com.kaddie.app.data.repository.RoomTransactionRunner
import com.kaddie.app.data.settings.SettingsRepository
import com.kaddie.app.data.sync.OutboxRecorder
import com.kaddie.app.data.sync.RoomSyncStore
import com.kaddie.app.data.sync.SharedPrefsSyncPreferences
import com.kaddie.app.data.sync.WorkManagerSyncScheduler
import com.kaddie.app.domain.matching.ProductMatchingService
import com.kaddie.app.domain.repository.ShoppingListRepository
import com.kaddie.app.domain.sync.SyncEngine
import com.kaddie.app.domain.sync.SyncManager
import com.kaddie.app.domain.sync.SyncStatus
import com.kaddie.app.domain.usecase.AddShoppingListItemUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency graph: small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = KaddieDatabase.create(context)
    private val dictionaryInitializer = DictionaryInitializer(context, database)
    private val outbox = OutboxRecorder(database.syncDao())

    val settings = SettingsRepository(context)
    private val tokenManager = TokenManager(
        store = KeystoreTokenStore(context),
        endpoint = CognitoTokenEndpoint(
            tokenUrl = "${BuildConfig.COGNITO_DOMAIN}/oauth2/token",
            clientId = BuildConfig.COGNITO_CLIENT_ID,
            client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
        ),
    )
    val authRepository: AuthRepository = CognitoAuthRepository(
        context = context,
        domain = BuildConfig.COGNITO_DOMAIN,
        clientId = BuildConfig.COGNITO_CLIENT_ID,
        redirectUri = BuildConfig.COGNITO_REDIRECT_URI,
        logoutUri = BuildConfig.COGNITO_LOGOUT_URI,
        tokens = tokenManager,
    )
    val remoteDataSource: ShoppingRemoteDataSource = KaddieApiFactory.create(
        baseUrl = BuildConfig.API_BASE_URL,
        auth = authRepository,
    )
    val shoppingLists: ShoppingListRepository = RoomShoppingListRepository(database, outbox)
    val matching = ProductMatchingService(
        dictionary = RoomProductDictionaryRepository(database, outbox),
        awaitReady = dictionaryInitializer::awaitReady,
    )
    val addItem = AddShoppingListItemUseCase(shoppingLists, matching, RoomTransactionRunner(database))

    val syncStatus = SyncStatus()
    private val syncStore = RoomSyncStore(database, outbox, onDictionaryChanged = matching::invalidate)
    val syncEngine = SyncEngine(
        store = syncStore,
        remote = remoteDataSource,
        prefs = SharedPrefsSyncPreferences(context),
        status = syncStatus,
        currentUserId = { authRepository.currentUser.value?.id },
    )
    val syncManager = SyncManager(syncStore, WorkManagerSyncScheduler(context), syncStatus, appScope)

    init {
        appScope.launch { dictionaryInitializer.initialize() }
        syncManager.start()
    }
}

class KaddieApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
