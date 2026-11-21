package com.raavivi.sysmon.core.data.autofix

import com.raavivi.sysmon.core.model.AutoFixReplyBody
import com.raavivi.sysmon.core.model.AutoFixResponse
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall

/** The Auto fix agent's REST surface. A 404 on any call means the feature is off server-side. */
interface AutoFixRepository {
    suspend fun status(): ApiResult<AutoFixResponse>

    /** 409 when a run is already going. */
    suspend fun start(): ApiResult<AutoFixResponse>

    /** 400 for empty or over-long text, 409 when a follow-up is not allowed. */
    suspend fun reply(text: String): ApiResult<AutoFixResponse>
}

class HttpAutoFixRepository(private val api: ApiProvider) : AutoFixRepository {
    override suspend fun status(): ApiResult<AutoFixResponse> = safeCall { api.api.autofixStatus() }

    override suspend fun start(): ApiResult<AutoFixResponse> = safeCall { api.api.autofixStart() }

    override suspend fun reply(text: String): ApiResult<AutoFixResponse> =
        safeCall { api.api.autofixReply(AutoFixReplyBody(text)) }
}
