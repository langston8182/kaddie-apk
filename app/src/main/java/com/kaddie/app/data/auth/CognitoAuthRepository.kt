package com.kaddie.app.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume

class SignInCancelledException : Exception("Connexion annulée")

class SignInFailedException(message: String) : Exception(message)

/**
 * Google sign-in through the Cognito hosted UI: authorization code + PKCE (AppAuth), Custom Tabs
 * for the browser step. Tokens live in [TokenManager]; this class only runs the interactive flows.
 */
class CognitoAuthRepository(
    context: Context,
    private val domain: String,
    private val clientId: String,
    private val redirectUri: String,
    private val logoutUri: String,
    private val tokens: TokenManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthRepository {
    private val appContext = context.applicationContext
    private val authService by lazy { AuthorizationService(appContext) }
    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("$domain/oauth2/authorize"),
        Uri.parse("$domain/oauth2/token"),
    )

    override val currentUser = tokens.user

    override suspend fun accessToken(): String? = tokens.accessToken()

    override suspend fun refreshAccessToken(failedToken: String?): String? = tokens.refreshAccessToken(failedToken)

    override fun createSignInIntent(): Intent {
        val request = AuthorizationRequest.Builder(serviceConfig, clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
            .setScope("openid email profile")
            .setAdditionalParameters(mapOf("identity_provider" to "Google"))
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    override suspend fun completeSignIn(data: Intent?): Result<User> {
        if (data == null) return Result.failure(SignInCancelledException())
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) {
            val cancelled = error == null ||
                (error.type == AuthorizationException.TYPE_GENERAL_ERROR && error.code == CANCELLED_CODE)
            return Result.failure(
                if (cancelled) SignInCancelledException() else SignInFailedException(error.errorDescription ?: "Connexion refusée"),
            )
        }
        val tokenResponse = exchange(response) ?: return Result.failure(SignInFailedException("Échange du code impossible"))
        val accessToken = tokenResponse.accessToken ?: return Result.failure(SignInFailedException("Jeton manquant"))
        val user = tokens.signIn(
            AuthTokens(
                accessToken = accessToken,
                idToken = tokenResponse.idToken,
                refreshToken = tokenResponse.refreshToken,
                expiresAtMs = tokenResponse.accessTokenExpirationTime ?: (clock() + DEFAULT_LIFETIME_MS),
            ),
        )
        return if (user != null) Result.success(user) else {
            tokens.signOut()
            Result.failure(SignInFailedException("Jeton illisible"))
        }
    }

    private suspend fun exchange(response: AuthorizationResponse): TokenResponse? =
        suspendCancellableCoroutine { continuation ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, _ ->
                continuation.resume(tokenResponse)
            }
        }

    override suspend fun signOut() = tokens.signOut()

    override fun createSignOutIntent(): Intent {
        val uri = Uri.parse("$domain/logout").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("logout_uri", logoutUri)
            .build()
        return CustomTabsIntent.Builder().build().intent.apply { data = uri }
    }

    private companion object {
        const val DEFAULT_LIFETIME_MS = 55 * 60 * 1000L
        /** AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW */
        const val CANCELLED_CODE = 1
    }
}
