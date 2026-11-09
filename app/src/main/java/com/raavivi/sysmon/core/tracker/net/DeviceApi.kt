package com.raavivi.sysmon.core.tracker.net

import com.raavivi.sysmon.core.tracker.model.AckBody
import com.raavivi.sysmon.core.tracker.model.CommandsResponse
import com.raavivi.sysmon.core.tracker.model.OkResponse
import com.raavivi.sysmon.core.tracker.model.PairBody
import com.raavivi.sysmon.core.tracker.model.PairResponse
import com.raavivi.sysmon.core.tracker.model.PushBody
import com.raavivi.sysmon.core.tracker.model.ReportBody
import com.raavivi.sysmon.core.tracker.model.ReportResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

const val TOKEN_HEADER = "X-Device-Token"

/** The device routes of sys-mon's Find Me API. Pairing needs no token. */
interface DeviceApi {
    @POST("api/findme/device/pair")
    suspend fun pair(@Body body: PairBody): PairResponse

    @POST("api/findme/device/report")
    suspend fun report(@Header(TOKEN_HEADER) token: String, @Body body: ReportBody): ReportResponse

    @POST("api/findme/device/push")
    suspend fun push(@Header(TOKEN_HEADER) token: String, @Body body: PushBody): OkResponse

    @GET("api/findme/device/commands")
    suspend fun commands(@Header(TOKEN_HEADER) token: String): CommandsResponse

    @POST("api/findme/device/commands/{id}")
    suspend fun ack(
        @Header(TOKEN_HEADER) token: String,
        @Path("id") id: String,
        @Body body: AckBody,
    ): OkResponse

    @DELETE("api/findme/device")
    suspend fun unpair(@Header(TOKEN_HEADER) token: String): OkResponse
}
