package com.kaddie.app.data.auth

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/** The signed-in user. [id] is the Cognito `sub`, which is also what the API uses as user id. */
data class User(val id: String, val displayName: String, val email: String? = null)

/** Access to the user's session, behind an interface so the Cognito implementation can be faked. */
interface AuthRepository {
    /** null when signed out. The app stays fully usable offline either way. */
    val currentUser: StateFlow<User?>

    /** A valid (refreshed if about to expire) access token, or null when signed out / refresh impossible. */
    suspend fun accessToken(): String?

    /**
     * Renews the access token after the API answered 401 for [failedToken]. If another call already
     * renewed it, the current token is returned without a new network call.
     */
    suspend fun refreshAccessToken(failedToken: String?): String?

    /** Intent to start (for result) the Google sign-in through the Cognito hosted UI (code + PKCE). */
    fun createSignInIntent(): Intent

    /** Exchanges the authorization result returned to the activity; the failure is a user-facing error. */
    suspend fun completeSignIn(data: Intent?): Result<User>

    /** Forgets the tokens on this device (local data is kept). */
    suspend fun signOut()

    /** Intent opening the Cognito `/logout` page (returns to the app via `kaddie://auth/logout`). */
    fun createSignOutIntent(): Intent
}
