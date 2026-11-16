package com.kaddie.app.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
private data class StoredTokens(
    val accessToken: String,
    val idToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long,
)

/** Tokens encrypted with an AES-GCM key held in the Android Keystore (never leaves the device). */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("kaddie_tokens", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AuthTokens? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
            val stored = json.decodeFromString<StoredTokens>(String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8))
            AuthTokens(stored.accessToken, stored.idToken, stored.refreshToken, stored.expiresAtMs)
        } catch (e: Exception) {
            // Unreadable (key lost, corrupted): treat as signed out.
            clear()
            null
        }
    }

    override fun save(tokens: AuthTokens) {
        val plain = json.encodeToString(StoredTokens.serializer(), StoredTokens(tokens.accessToken, tokens.idToken, tokens.refreshToken, tokens.expiresAtMs))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kaddie_tokens_key"
        const val KEY_BLOB = "tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Renews the access token on the Cognito token endpoint (public client: no secret). */
class CognitoTokenEndpoint(
    private val tokenUrl: String,
    private val clientId: String,
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenEndpoint {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun refresh(refreshToken: String): RefreshResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .build()
        try {
            client.newCall(Request.Builder().url(tokenUrl).post(body).build()).execute().use { response ->
                val text = response.body.string()
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                when {
                    response.isSuccessful -> {
                        val access = root?.get("access_token")?.jsonPrimitive?.content ?: return@use RefreshResult.TemporaryFailure
                        val expiresIn = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                        RefreshResult.Success(
                            AuthTokens(access, root["id_token"]?.jsonPrimitive?.content, root["refresh_token"]?.jsonPrimitive?.content, clock() + expiresIn * 1000),
                        )
                    }
                    response.code in 400..499 && root?.get("error")?.jsonPrimitive?.content == "invalid_grant" -> RefreshResult.InvalidGrant
                    else -> RefreshResult.TemporaryFailure
                }
            }
        } catch (e: IOException) {
            RefreshResult.TemporaryFailure
        }
    }
}
