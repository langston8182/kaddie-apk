package com.kaddie.app.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthTokens(
    val accessToken: String,
    val idToken: String?,
    val refreshToken: String?,
    /** Epoch millis at which the access token expires. */
    val expiresAtMs: Long,
)

/** Secure persistence of the tokens (Android Keystore in production, in memory in tests). */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

sealed interface RefreshResult {
    data class Success(val tokens: AuthTokens) : RefreshResult

    /** The refresh token was rejected (revoked, expired): the user must sign in again. */
    data object InvalidGrant : RefreshResult

    /** Network or server trouble: keep the session and try again later. */
    data object TemporaryFailure : RefreshResult
}

interface TokenEndpoint {
    suspend fun refresh(refreshToken: String): RefreshResult
}

/**
 * Keeps the session: serves a valid access token, renews it with the refresh token when it is about to
 * expire (one renewal at a time), and signs out locally when the refresh token is rejected.
 */
class TokenManager(
    private val store: TokenStore,
    private val endpoint: TokenEndpoint,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _user = MutableStateFlow(store.load()?.let(::userOf))
    val user: StateFlow<User?> = _user.asStateFlow()

    suspend fun accessToken(): String? = mutex.withLock {
        val tokens = store.load() ?: return@withLock null
        if (tokens.expiresAtMs - EXPIRY_SKEW_MS > clock()) tokens.accessToken else renew(tokens)
    }

    suspend fun refreshAccessToken(failedToken: String?): String? = mutex.withLock {
        val tokens = store.load() ?: return@withLock null
        // Renewed meanwhile by another request: no need for another round trip.
        if (failedToken != null && tokens.accessToken != failedToken && tokens.expiresAtMs - EXPIRY_SKEW_MS > clock()) {
            return@withLock tokens.accessToken
        }
        renew(tokens)
    }

    suspend fun signIn(tokens: AuthTokens): User? = mutex.withLock {
        store.save(tokens)
        userOf(tokens).also { _user.value = it }
    }

    suspend fun signOut() = mutex.withLock {
        store.clear()
        _user.value = null
    }

    private suspend fun renew(current: AuthTokens): String? {
        val refreshToken = current.refreshToken
        if (refreshToken == null) {
            if (current.expiresAtMs <= clock()) endSession()
            return null
        }
        return when (val result = endpoint.refresh(refreshToken)) {
            is RefreshResult.Success -> {
                // Cognito does not return a new refresh token: keep the current one.
                val merged = result.tokens.copy(
                    refreshToken = result.tokens.refreshToken ?: refreshToken,
                    idToken = result.tokens.idToken ?: current.idToken,
                )
                store.save(merged)
                userOf(merged)?.let { _user.value = it }
                merged.accessToken
            }
            RefreshResult.InvalidGrant -> {
                endSession()
                null
            }
            RefreshResult.TemporaryFailure -> null
        }
    }

    private fun endSession() {
        store.clear()
        _user.value = null
    }

    private fun userOf(tokens: AuthTokens): User? = JwtClaims.userOf(tokens.idToken ?: tokens.accessToken)

    private companion object {
        const val EXPIRY_SKEW_MS = 60_000L
    }
}
