package com.kaddie.app.data.remote

import com.kaddie.app.data.auth.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Sends `Authorization: Bearer <access token>` (renewed beforehand if about to expire). */
class BearerInterceptor(private val auth: AuthRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { auth.accessToken() }
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header(AUTHORIZATION, "Bearer $token").build()
        }
        return chain.proceed(request)
    }
}

/** On a 401, renews the token and retries the request once; otherwise the 401 is passed on. */
class TokenRefreshAuthenticator(private val auth: AuthRepository) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        val failedToken = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
        val fresh = runBlocking { auth.refreshAccessToken(failedToken) } ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, "Bearer $fresh").build()
    }
}

private const val AUTHORIZATION = "Authorization"

object KaddieApiFactory {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun create(baseUrl: String, auth: AuthRepository): ShoppingRemoteDataSource {
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(auth))
            .authenticator(TokenRefreshAuthenticator(auth))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return RetrofitShoppingRemoteDataSource(retrofit.create(KaddieApi::class.java), json)
    }
}
