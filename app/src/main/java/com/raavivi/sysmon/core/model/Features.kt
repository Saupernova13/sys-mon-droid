package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/features` / `POST /api/settings` payload. The top-level booleans are
 * *effective* visibility (flag AND prerequisites available); [detail] carries the
 * raw toggle state plus availability hints for the settings screen. When a gated
 * feature (godot / model_log / whatsapp) is off, its whole router 404s server-side.
 */
@Serializable
data class FeaturesResponse(
    val whatsapp: Boolean = false,
    val modelLog: Boolean = false,
    val ollamaProxy: Boolean = false,
    val godot: Boolean = false,
    val power: Boolean = false,
    val remoteControl: Boolean = false,
    /** Config-driven: the server has a Firebase key and can send FCM pushes. */
    val push: Boolean = false,
    val detail: FeatureDetail? = null,
    /** Only present on `POST /api/settings`: flags needing a server restart. */
    val restartRequired: List<String> = emptyList(),
)

@Serializable
data class FeatureDetail(
    val whatsapp: WhatsAppDetail = WhatsAppDetail(),
    val modelLog: EnabledDetail = EnabledDetail(),
    val ollamaProxy: ProxyDetail = ProxyDetail(),
    val godot: GodotDetail = GodotDetail(),
    val power: PowerFeatureDetail = PowerFeatureDetail(),
    val remoteControl: RemoteControlDetail = RemoteControlDetail(),
    val push: PushFeatureDetail = PushFeatureDetail(),
)

@Serializable
data class PushFeatureDetail(val configured: Boolean = false, val devices: Int = 0)

@Serializable
data class WhatsAppDetail(val enabled: Boolean = false, val wacliFound: Boolean = false)

@Serializable
data class EnabledDetail(val enabled: Boolean = false)

@Serializable
data class ProxyDetail(val enabled: Boolean = false, val running: Boolean = false)

@Serializable
data class GodotDetail(val enabled: Boolean = false, val pathSet: Boolean = false)

@Serializable
data class PowerFeatureDetail(val enabled: Boolean = false, val urlSet: Boolean = false)

@Serializable
data class RemoteControlDetail(val shortcutFound: Boolean = false)

/** Partial update for `POST /api/settings`; unset fields are left unchanged. */
@Serializable
data class FeaturePatch(
    val whatsapp: Boolean? = null,
    val modelLog: Boolean? = null,
    val ollamaProxy: Boolean? = null,
    val godot: Boolean? = null,
    val power: Boolean? = null,
)
