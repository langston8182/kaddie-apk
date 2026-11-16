package com.kaddie.app

import android.content.Intent
import com.kaddie.app.data.auth.AuthRepository
import com.kaddie.app.data.auth.AuthTokens
import com.kaddie.app.data.auth.CognitoTokenEndpoint
import com.kaddie.app.data.auth.JwtClaims
import com.kaddie.app.data.auth.RefreshResult
import com.kaddie.app.data.auth.TokenEndpoint
import com.kaddie.app.data.auth.TokenManager
import com.kaddie.app.data.auth.TokenStore
import com.kaddie.app.data.auth.User
import com.kaddie.app.data.remote.AddResult
import com.kaddie.app.data.remote.BearerInterceptor
import com.kaddie.app.data.remote.KaddieApiFactory
import com.kaddie.app.data.remote.RemoteAddItem
import com.kaddie.app.data.remote.RemoteError
import com.kaddie.app.data.remote.RemoteResult
import com.kaddie.app.data.remote.TokenRefreshAuthenticator
import com.kaddie.app.domain.model.MeasureUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

private fun jwt(vararg claims: Pair<String, String>): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val payload = claims.joinToString(",", "{", "}") { "\"${it.first}\":\"${it.second}\"" }
    return "${encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())}.${encoder.encodeToString(payload.toByteArray())}.sig"
}

private class MemoryTokenStore(var tokens: AuthTokens? = null) : TokenStore {
    override fun load() = tokens
    override fun save(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class FakeEndpoint(var next: () -> RefreshResult) : TokenEndpoint {
    var calls = 0
    override suspend fun refresh(refreshToken: String): RefreshResult {
        calls++
        return next()
    }
}

private const val NOW = 1_000_000_000L

private fun tokens(access: String = "access-1", refresh: String? = "refresh-1", expiresAt: Long = NOW + 3_600_000, id: String? = jwt("sub" to "sub-123", "email" to "me@example.com")) =
    AuthTokens(access, id, refresh, expiresAt)

class JwtClaimsTests {
    @Test fun readsSubEmailAndName() {
        val user = JwtClaims.userOf(jwt("sub" to "abc", "email" to "me@example.com", "name" to "Marie"))
        assertEquals(User("abc", "Marie", "me@example.com"), user)
    }

    @Test fun fallsBackToEmailThenUsername() {
        assertEquals("me@example.com", JwtClaims.userOf(jwt("sub" to "abc", "email" to "me@example.com"))?.displayName)
        assertEquals("google_123", JwtClaims.userOf(jwt("sub" to "abc", "username" to "google_123"))?.displayName)
    }

    @Test fun rejectsGarbage() {
        assertNull(JwtClaims.userOf("not-a-jwt"))
        assertNull(JwtClaims.userOf(jwt("email" to "no-sub@example.com")))
    }
}

class TokenManagerTests {
    private val store = MemoryTokenStore()
    private var now = NOW
    private val endpoint = FakeEndpoint { RefreshResult.Success(tokens("access-2", null, now + 3_600_000, null)) }
    private val manager = TokenManager(store, endpoint) { now }

    @Test fun signedOutHasNoToken() = runBlocking {
        assertNull(manager.accessToken())
        assertNull(manager.user.value)
    }

    @Test fun signInStoresTokensAndExposesTheUser() = runBlocking {
        val user = manager.signIn(tokens())
        assertEquals("sub-123", user?.id)
        assertEquals("sub-123", manager.user.value?.id)
        assertEquals("access-1", manager.accessToken())
        assertEquals(0, endpoint.calls)
    }

    @Test fun sessionSurvivesARestart() = runBlocking {
        store.save(tokens())
        assertEquals("sub-123", TokenManager(store, endpoint) { now }.user.value?.id)
    }

    @Test fun renewsAnAboutToExpireTokenKeepingTheRefreshToken() = runBlocking {
        manager.signIn(tokens(expiresAt = NOW + 30_000)) // inside the 60 s safety margin
        assertEquals("access-2", manager.accessToken())
        assertEquals(1, endpoint.calls)
        assertEquals("refresh-1", store.tokens?.refreshToken)
        assertEquals("sub-123", manager.user.value?.id) // id token kept
    }

    @Test fun rejectedRefreshTokenSignsOut() = runBlocking {
        manager.signIn(tokens(expiresAt = NOW - 1))
        endpoint.next = { RefreshResult.InvalidGrant }
        assertNull(manager.accessToken())
        assertNull(manager.user.value)
        assertNull(store.tokens)
    }

    @Test fun temporaryFailureKeepsTheSession() = runBlocking {
        manager.signIn(tokens(expiresAt = NOW - 1))
        endpoint.next = { RefreshResult.TemporaryFailure }
        assertNull(manager.accessToken())
        assertNotNull(manager.user.value)
        assertNotNull(store.tokens)
    }

    @Test fun expiredWithoutRefreshTokenEndsTheSession() = runBlocking {
        manager.signIn(tokens(refresh = null, expiresAt = NOW - 1))
        assertNull(manager.accessToken())
        assertNull(manager.user.value)
    }

    @Test fun forcedRefreshSkipsTheNetworkIfAnotherCallAlreadyRenewed() = runBlocking {
        manager.signIn(tokens(access = "fresh"))
        assertEquals("fresh", manager.refreshAccessToken("stale"))
        assertEquals(0, endpoint.calls)
        assertEquals("access-2", manager.refreshAccessToken("fresh")) // that very token was rejected
        assertEquals(1, endpoint.calls)
    }

    @Test fun concurrentRenewalsHitTheEndpointOnce() = runBlocking {
        manager.signIn(tokens(expiresAt = NOW - 1))
        val results = (1..5).map { async { manager.accessToken() } }.awaitAll()
        assertTrue(results.all { it == "access-2" })
        assertEquals(1, endpoint.calls)
    }

    @Test fun signOutClearsEverything() = runBlocking {
        manager.signIn(tokens())
        manager.signOut()
        assertNull(manager.user.value)
        assertNull(store.tokens)
    }
}

private class FakeAuth(private val manager: TokenManager) : AuthRepository {
    override val currentUser: StateFlow<User?> = manager.user
    override suspend fun accessToken() = manager.accessToken()
    override suspend fun refreshAccessToken(failedToken: String?) = manager.refreshAccessToken(failedToken)
    override fun createSignInIntent(): Intent = error("unused")
    override suspend fun completeSignIn(data: Intent?): Result<User> = error("unused")
    override suspend fun signOut() = manager.signOut()
    override fun createSignOutIntent(): Intent = error("unused")
}

class BearerAuthenticationTests {
    private val server = MockWebServer()
    private val store = MemoryTokenStore()
    private val endpoint = FakeEndpoint { RefreshResult.Success(tokens("access-2", null, NOW + 3_600_000, null)) }
    private val auth = FakeAuth(TokenManager(store, endpoint) { NOW })
    private val client = OkHttpClient.Builder()
        .addInterceptor(BearerInterceptor(auth))
        .authenticator(TokenRefreshAuthenticator(auth))
        .build()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun call() = client.newCall(Request.Builder().url(server.url("/v1/lists")).build()).execute()

    @Test fun sendsTheBearerTokenAndNeverTheDevHeader() {
        store.save(tokens())
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())
        call().use { assertEquals(200, it.code) }
        val request = server.takeRequest()
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertNull(request.headers["x-user-id"])
    }

    @Test fun refreshesOnceOnA401AndRetries() {
        store.save(tokens())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        call().use { assertEquals(200, it.code) }

        assertEquals(1, endpoint.calls)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
    }

    @Test fun givesUpAfterOneRetry() {
        store.save(tokens())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build())

        call().use { assertEquals(401, it.code) }

        assertEquals(2, server.requestCount)
        assertEquals(1, endpoint.calls)
    }

    @Test fun signedOutRequestsGoWithoutCredentialsAndWithoutRefreshing() {
        server.enqueue(MockResponse.Builder().code(401).build())
        call().use { assertEquals(401, it.code) }
        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals(0, endpoint.calls)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedRefreshPassesThe401On() {
        store.save(tokens())
        endpoint.next = { RefreshResult.TemporaryFailure }
        server.enqueue(MockResponse.Builder().code(401).build())
        call().use { assertEquals(401, it.code) }
        assertEquals(1, server.requestCount)
    }
}

class CognitoTokenEndpointTests {
    private val server = MockWebServer()
    private val endpoint by lazy {
        CognitoTokenEndpoint(server.url("/oauth2/token").toString(), "client-1", OkHttpClient()) { NOW }
    }

    @Before fun setUp() = server.start()
    @After fun tearDown() = runCatching { server.close() }.let { }

    @Test fun refreshesWithAPublicClientFormPost() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"new","id_token":"id","expires_in":1800,"token_type":"Bearer"}""").build())

        val result = endpoint.refresh("refresh-1") as RefreshResult.Success

        assertEquals("new", result.tokens.accessToken)
        assertEquals("id", result.tokens.idToken)
        assertNull(result.tokens.refreshToken)
        assertEquals(NOW + 1_800_000, result.tokens.expiresAtMs)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("client_id=client-1"))
        assertTrue(body.contains("refresh_token=refresh-1"))
        assertNull(request.headers["Authorization"])
    }

    @Test fun invalidGrantMeansSignInAgain() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
        assertEquals(RefreshResult.InvalidGrant, endpoint.refresh("revoked"))
    }

    @Test fun serverErrorsAreTemporary() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("oops").build())
        assertEquals(RefreshResult.TemporaryFailure, endpoint.refresh("r"))
    }

    @Test fun networkFailureIsTemporary() = runBlocking {
        server.close()
        assertEquals(RefreshResult.TemporaryFailure, endpoint.refresh("r"))
    }
}

class RemoteErrorMappingTests {
    private val server = MockWebServer()
    private val store = MemoryTokenStore(tokens())
    private val auth = FakeAuth(TokenManager(store, FakeEndpoint { RefreshResult.TemporaryFailure }) { NOW })
    private val remote by lazy { KaddieApiFactory.create(server.url("/v1/").toString(), auth) }

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val item = """{"id":"i1","listId":"l1","productId":"p","name":"Lait","quantity":2,"unit":"L","purchasedQuantity":0,"isPurchased":false,"note":"","createdAt":"2026-11-16T10:00:00.000Z","updatedAt":"2026-11-16T10:00:01.500Z"}"""
    private val request = RemoteAddItem("i1", "p", "Lait", 2.0, MeasureUnit.L, "")

    @Test fun parsesAnAddedItemAndSendsTheAuthorizationHeader() = runBlocking {
        respond(201, """{"result":"ADDED","item":$item}""")
        val result = remote.addItem("l1", request) as RemoteResult.Success
        assertEquals(AddResult.ADDED, result.value.result)
        assertEquals(MeasureUnit.L, result.value.item.unit)
        assertEquals(1_794_823_201_500L, result.value.item.updatedAt)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
    }

    @Test fun mapsUnitConflictWithTheExistingItem() = runBlocking {
        respond(409, """{"message":"Unités incompatibles","details":{"code":"UNIT_CONFLICT","existing":$item}}""")
        val error = (remote.addItem("l1", request) as RemoteResult.Failure).error as RemoteError.UnitConflict
        assertEquals("i1", error.existing?.id)
    }

    @Test fun mapsOtherConflictsAndStatusCodes() = runBlocking {
        respond(409, """{"message":"Ce produit existe déjà"}""")
        assertEquals(RemoteError.Conflict("Ce produit existe déjà"), (remote.upsertProduct("p", "x", com.kaddie.app.domain.model.ProductCategory.OTHER) as RemoteResult.Failure).error)

        respond(404, """{"message":"Liste non trouvée"}""")
        assertEquals(RemoteError.NotFound, (remote.deleteList("l1") as RemoteResult.Failure).error)

        respond(400, """{"message":"Données invalides","details":{"name":"Champ obligatoire"}}""")
        assertEquals(RemoteError.Invalid("Données invalides"), (remote.upsertList("l1", "") as RemoteResult.Failure).error)

        respond(500, """{"message":"Erreur interne du serveur"}""")
        assertEquals(RemoteError.Server(500), (remote.fetchLists(null) as RemoteResult.Failure).error)
    }

    @Test fun unauthorizedAfterAFailedRefreshIsReported() = runBlocking {
        respond(401, """{"message":"Jeton invalide ou expiré"}""")
        assertEquals(RemoteError.Unauthorized, (remote.fetchLists(null) as RemoteResult.Failure).error)
    }

    @Test fun networkFailureIsReported() = runBlocking {
        server.close()
        assertTrue((remote.fetchLists(null) as RemoteResult.Failure).error is RemoteError.Network)
    }

    @Test fun sendsUpdatedSinceAsIso() = runBlocking {
        respond(200, "[]")
        remote.fetchLists(1_794_823_200_000L)
        assertEquals("2026-11-16T10:00:00Z", server.takeRequest().url.queryParameter("updatedSince"))
    }
}
