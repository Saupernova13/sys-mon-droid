package com.raavivi.sysmon.core.push

/** How loud a watchdog event is on the phone. */
enum class WatchdogLevel { ALERT, UPDATE }

/**
 * A watchdog push (`type=watchdog`), parsed from its FCM data map (all strings).
 * [level] decides the channel: null means the event is not notified (paused,
 * resumed, or an event kind this build does not know).
 */
data class WatchdogAlert(
    val event: String,
    val id: String,
    val name: String,
    val detail: String,
    val state: String,
    val ts: Double?,
    val eid: String,
) {
    val level: WatchdogLevel? get() = levelOf(event)

    /** "Jellyfin is down", "Jellyfin was restarted", "Jellyfin is back", ... */
    fun title(): String = when (event) {
        "degraded" -> "$name is down"
        "healing" -> "$name was restarted"
        "healed", "recovered" -> "$name is back"
        "heal_failed" -> "Restarting $name failed"
        "given_up" -> "Watchdog gave up on $name"
        "crashed" -> "$name crashed"
        "filed" -> "$name: filed a loose file"
        "loose_unnamed" -> "$name: loose file needs a name"
        "job_failed" -> "$name: job failed"
        else -> "$name: $event"
    }

    companion object {
        const val TYPE = "watchdog"

        fun levelOf(event: String): WatchdogLevel? = when (event) {
            "degraded", "heal_failed", "given_up", "crashed", "loose_unnamed", "job_failed" -> WatchdogLevel.ALERT
            "healing", "healed", "recovered", "filed" -> WatchdogLevel.UPDATE
            else -> null
        }

        /** Null unless the map is a `watchdog` push with an event and a target id. */
        fun from(data: Map<String, String>): WatchdogAlert? {
            if (data["type"] != TYPE) return null
            val event = data["event"]?.takeIf { it.isNotBlank() } ?: return null
            val id = data["id"]?.takeIf { it.isNotBlank() } ?: return null
            return WatchdogAlert(
                event = event,
                id = id,
                name = data["name"]?.takeIf { it.isNotBlank() } ?: id,
                detail = data["detail"].orEmpty(),
                state = data["state"].orEmpty(),
                ts = data["ts"]?.toDoubleOrNull()?.takeIf { it.isFinite() },
                eid = data["eid"].orEmpty(),
            )
        }

        /** Short label for the event list on the Health screen. */
        fun label(event: String): String = when (event) {
            "degraded" -> "Down"
            "healing" -> "Restarting"
            "healed" -> "Restarted"
            "heal_failed" -> "Restart failed"
            "given_up" -> "Gave up"
            "recovered" -> "Back"
            "paused" -> "Paused"
            "resumed" -> "Resumed"
            "crashed" -> "Crashed"
            "filed" -> "Filed"
            "loose_unnamed" -> "Needs a name"
            "job_failed" -> "Job failed"
            else -> event
        }
    }
}
