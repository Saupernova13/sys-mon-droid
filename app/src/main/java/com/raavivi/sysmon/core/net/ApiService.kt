package com.raavivi.sysmon.core.net

import com.raavivi.sysmon.core.model.ClearResponse
import com.raavivi.sysmon.core.model.DrivesResponse
import com.raavivi.sysmon.core.model.Favorite
import com.raavivi.sysmon.core.model.FavoriteBody
import com.raavivi.sysmon.core.model.FeaturePatch
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.FileProperties
import com.raavivi.sysmon.core.model.FolderSize
import com.raavivi.sysmon.core.model.FsCopyBody
import com.raavivi.sysmon.core.model.FsListResponse
import com.raavivi.sysmon.core.model.FsMkdirBody
import com.raavivi.sysmon.core.model.FsMoveBody
import com.raavivi.sysmon.core.model.FsReadResponse
import com.raavivi.sysmon.core.model.FsRenameBody
import com.raavivi.sysmon.core.model.FsWriteBody
import com.raavivi.sysmon.core.model.HistoryRangeResponse
import com.raavivi.sysmon.core.model.HistoryRecentResponse
import com.raavivi.sysmon.core.model.KillBody
import com.raavivi.sysmon.core.model.KillPrepareBody
import com.raavivi.sysmon.core.model.KillPrepareResponse
import com.raavivi.sysmon.core.model.LoginRequest
import com.raavivi.sysmon.core.model.LoginResponse
import com.raavivi.sysmon.core.model.ModelLogMeta
import com.raavivi.sysmon.core.model.ModelLogResponse
import com.raavivi.sysmon.core.model.OkResponse
import com.raavivi.sysmon.core.model.PowerActionResponse
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.model.RecycleListResponse
import com.raavivi.sysmon.core.model.RecycleRestoreBody
import com.raavivi.sysmon.core.model.SearchBackend
import com.raavivi.sysmon.core.model.SearchResponse
import com.raavivi.sysmon.core.model.StatusResponse
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.core.model.VerifyResponse
import com.raavivi.sysmon.core.model.VersionInfo
import com.raavivi.sysmon.core.model.WaBackfill
import com.raavivi.sysmon.core.model.WaChatsResponse
import com.raavivi.sysmon.core.model.WaContactsResponse
import com.raavivi.sysmon.core.model.WaMessagesResponse
import com.raavivi.sysmon.core.model.WaPinReq
import com.raavivi.sysmon.core.model.WaSendText
import com.raavivi.sysmon.core.model.WaStatus
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The full sys-mon REST surface (per `docs/API.md`). WebSocket streams are handled
 * separately in [ApiProvider] / [WsStream] because OkHttp's WS API is used directly.
 */
interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("auth/verify")
    suspend fun verify(): VerifyResponse

    @POST("auth/logout")
    suspend fun logout(): OkResponse

    @POST("auth/logout-all")
    suspend fun logoutAll(): OkResponse

    @GET("api/version")
    suspend fun version(): VersionInfo

    // ── Features / settings ─────────────────────────────────────────────────────
    @GET("api/features")
    suspend fun features(): FeaturesResponse

    @POST("api/settings")
    suspend fun updateSettings(@Body patch: FeaturePatch): FeaturesResponse

    // ── Stats / history ────────────────────────────────────────────────────────
    @GET("api/snapshot")
    suspend fun snapshot(): SystemSnapshot

    @GET("api/history/recent")
    suspend fun historyRecent(@Query("seconds") seconds: Int = 600): HistoryRecentResponse

    @GET("api/history")
    suspend fun historyRange(
        @Query("start") start: Double,
        @Query("end") end: Double,
        @Query("limit") limit: Int = 5000,
        @Query("include_processes") includeProcesses: Boolean = false,
    ): HistoryRangeResponse

    @POST("api/history/backup")
    suspend fun historyBackup(): OkResponse

    // ── Power usage (smart plug) ─────────────────────────────────────────────────
    @GET("api/power-usage")
    suspend fun powerUsage(): PowerReading

    @GET("api/power-usage/history")
    suspend fun powerHistory(
        @Query("start") start: Double? = null,
        @Query("end") end: Double? = null,
        @Query("target_points") targetPoints: Int = 600,
    ): PowerHistoryResponse

    // ── Power / process ──────────────────────────────────────────────────────────
    @POST("api/power/restart")
    suspend fun restart(): PowerActionResponse

    @POST("api/power/shutdown")
    suspend fun shutdown(): PowerActionResponse

    @POST("api/process/kill/prepare")
    suspend fun killPrepare(@Body body: KillPrepareBody): KillPrepareResponse

    @POST("api/process/kill")
    suspend fun kill(@Body body: KillBody): OkResponse

    @POST("api/remote-control")
    suspend fun remoteControl(): StatusResponse

    // ── Files ────────────────────────────────────────────────────────────────────
    @GET("api/fs/list")
    suspend fun fsList(@Query("path") path: String): FsListResponse

    @GET("api/fs/read")
    suspend fun fsRead(@Query("path") path: String): FsReadResponse

    @POST("api/fs/write")
    suspend fun fsWrite(@Body body: FsWriteBody): OkResponse

    @POST("api/fs/copy")
    suspend fun fsCopy(@Body body: FsCopyBody): OkResponse

    @POST("api/fs/move")
    suspend fun fsMove(@Body body: FsMoveBody): OkResponse

    @DELETE("api/fs/delete")
    suspend fun fsDelete(@Query("path") path: String): OkResponse

    @POST("api/fs/rename")
    suspend fun fsRename(@Body body: FsRenameBody): OkResponse

    @POST("api/fs/mkdir")
    suspend fun fsMkdir(@Body body: FsMkdirBody): OkResponse

    @GET("api/fs/drives")
    suspend fun fsDrives(): DrivesResponse

    @GET("api/fs/properties")
    suspend fun fsProperties(@Query("path") path: String): FileProperties

    @GET("api/fs/folder-size")
    suspend fun fsFolderSize(
        @Query("path") path: String,
        @Query("max_seconds") maxSeconds: Double = 5.0,
    ): FolderSize

    // Recycle bin
    @GET("api/fs/recycle")
    suspend fun recycleList(): RecycleListResponse

    @POST("api/fs/recycle/restore")
    suspend fun recycleRestore(@Body body: RecycleRestoreBody): OkResponse

    @DELETE("api/fs/recycle/item")
    suspend fun recycleDelete(@Query("i_file") iFile: String): OkResponse

    @DELETE("api/fs/recycle/clear")
    suspend fun recycleClear(): OkResponse

    // Search
    @GET("api/fs/search")
    suspend fun fsSearch(
        @Query("q") q: String,
        @Query("root") root: String? = null,
        @Query("ext") ext: String? = null,
        @Query("min_size") minSize: Long? = null,
        @Query("max_size") maxSize: Long? = null,
        @Query("limit") limit: Int = 500,
    ): SearchResponse

    @GET("api/fs/search/backend")
    suspend fun fsSearchBackend(): SearchBackend

    // Favorites
    @GET("api/favorites")
    suspend fun favorites(): List<Favorite>

    @POST("api/favorites")
    suspend fun addFavorite(@Body body: FavoriteBody): OkResponse

    @DELETE("api/favorites")
    suspend fun removeFavorite(@Query("path") path: String): OkResponse

    // ── Model log ──────────────────────────────────────────────────────────────
    @GET("api/models/log")
    suspend fun modelLog(
        @Query("service") service: String? = null,
        @Query("model") model: String? = null,
        @Query("search") search: String? = null,
        @Query("since_ts") sinceTs: Double? = null,
        @Query("before_id") beforeId: Long? = null,
        @Query("limit") limit: Int = 200,
    ): ModelLogResponse

    @GET("api/models/meta")
    suspend fun modelMeta(): ModelLogMeta

    @POST("api/models/clear")
    suspend fun modelClear(): ClearResponse

    // ── WhatsApp ─────────────────────────────────────────────────────────────────
    @GET("api/whatsapp/status")
    suspend fun waStatus(): WaStatus

    @GET("api/whatsapp/chats")
    suspend fun waChats(@Query("limit") limit: Int = 100): WaChatsResponse

    @GET("api/whatsapp/messages")
    suspend fun waMessages(
        @Query("chat") chat: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): WaMessagesResponse

    @GET("api/whatsapp/contacts")
    suspend fun waContacts(
        @Query("q") q: String = "",
        @Query("limit") limit: Int = 100,
    ): WaContactsResponse

    @POST("api/whatsapp/send")
    suspend fun waSend(@Body body: WaSendText): OkResponse

    @POST("api/whatsapp/pin")
    suspend fun waPin(@Body body: WaPinReq): OkResponse

    @POST("api/whatsapp/backfill")
    suspend fun waBackfill(@Body body: WaBackfill): OkResponse
}
