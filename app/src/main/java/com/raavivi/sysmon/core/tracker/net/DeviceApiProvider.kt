package com.raavivi.sysmon.core.tracker.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.raavivi.sysmon.core.log.HttpLogInterceptor
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Owns the shared [OkHttpClient] and one Retrofit-built [DeviceApi] per server origin. */
class DeviceApiProvider {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLogInterceptor("DeviceHttp"))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apis = ConcurrentHashMap<String, DeviceApi>()

    /** The API for [baseUrl] (an origin such as `http://host:port`). */
    @OptIn(ExperimentalSerializationApi::class)
    fun forServer(baseUrl: String): DeviceApi = apis.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(okHttpClient)
            .addConverterFactory(TrackerJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeviceApi::class.java)
    }
}
