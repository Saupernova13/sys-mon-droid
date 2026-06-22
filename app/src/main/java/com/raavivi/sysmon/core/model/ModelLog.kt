package com.raavivi.sysmon.core.model

import com.raavivi.sysmon.core.net.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable

/** One row of the LLM audit trail (`services/model_log.py:_FIELDS`). */
@Serializable
data class ModelLogRow(
    val id: Long = 0,
    val ts: Double = 0.0,
    val service: String = "",
    val endpoint: String? = null,
    val method: String? = null,
    val model: String? = null,
    val callerIp: String? = null,
    val callerPort: Int? = null,
    val callerProc: String? = null,
    val message: String = "",
    val status: Int? = null,
    val durationMs: Double? = null,
    // The backend stores these as SQLite INTEGER (0/1), so accept int or bool.
    @Serializable(with = FlexibleBooleanSerializer::class)
    val coldStart: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class)
    val stream: Boolean = false,
    val reqBytes: Long? = null,
    val respBytes: Long? = null,
    val extra: String? = null,
)

@Serializable
data class ModelLogResponse(val items: List<ModelLogRow> = emptyList())

@Serializable
data class ModelLogMeta(
    val models: List<String> = emptyList(),
    val proxy: ProxyStatus? = null,
)

@Serializable
data class ProxyStatus(
    val running: Boolean? = null,
    val pid: Int? = null,
    val model: String? = null,
    val idleSeconds: Double? = null,
)

@Serializable
data class ClearResponse(val cleared: Int = 0)
