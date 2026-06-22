package com.raavivi.sysmon.core.net

import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Owns the OkHttp client, the Retrofit-built [ApiService], and the JWT used by both
 * REST and WebSocket calls. The base URL and token can change at runtime (the user
 * configures the server, then logs in), so [setBaseUrl] rebuilds the [ApiService]
 * while the single shared [OkHttpClient] simply reads the latest [token] per call.
 */
class ApiProvider(initialBaseUrl: String = DEFAULT_BASE_URL) {

    @Volatile
    var token: String? = null

    @Volatile
    private var baseUrl: String = normalizeBaseUrl(initialBaseUrl)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            val t = token
            val out = if (t != null && req.header("Authorization") == null) {
                req.newBuilder().header("Authorization", "Bearer $t").build()
            } else {
                req
            }
            chain.proceed(out)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    var api: ApiService = buildApi(baseUrl)
        private set

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildApi(base: String): ApiService =
        Retrofit.Builder()
            .baseUrl("$base/")
            .client(okHttpClient)
            .addConverterFactory(SysMonJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)

    fun setBaseUrl(input: String) {
        val normalized = normalizeBaseUrl(input)
        if (normalized == baseUrl) return
        baseUrl = normalized
        api = buildApi(normalized)
    }

    fun httpBaseUrl(): String = baseUrl

    /** Raw `/api/fs/file` URL for streaming (image preview, downloads). */
    fun fileUrl(path: String): String =
        "$baseUrl/api/fs/file".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()
            .toString()

    /**
     * Open a WebSocket on the shared client. OkHttp accepts http(s) URLs for the
     * handshake and upgrades them to ws(s) itself, so we keep [baseUrl] as-is and
     * just append the auth token + any extra query params.
     */
    fun openWebSocket(
        path: String,
        extraQuery: Map<String, String> = emptyMap(),
        listener: WebSocketListener,
    ): WebSocket {
        val builder = "$baseUrl$path".toHttpUrl().newBuilder()
        token?.let { builder.addQueryParameter("token", it) }
        extraQuery.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request = Request.Builder().url(builder.build()).build()
        return okHttpClient.newWebSocket(request, listener)
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:11037"

        /** Accepts bare host[:port], adds http:// if missing, strips trailing slash. */
        fun normalizeBaseUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return DEFAULT_BASE_URL
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "http://$s"
            }
            return s
        }
    }
}
