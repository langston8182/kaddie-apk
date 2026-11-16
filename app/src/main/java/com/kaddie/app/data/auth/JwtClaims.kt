package com.kaddie.app.data.auth

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads (without verifying: the API does) the claims of a Cognito JWT to display who is signed in. */
object JwtClaims {
    fun userOf(jwt: String): User? {
        val claims = payload(jwt) ?: return null
        val sub = claims.string("sub") ?: return null
        val email = claims.string("email")
        val name = claims.string("name") ?: email ?: claims.string("cognito:username") ?: claims.string("username") ?: "Compte Google"
        return User(id = sub, displayName = name, email = email)
    }

    private fun payload(jwt: String): JsonObject? {
        val part = jwt.split('.').getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(part)
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
