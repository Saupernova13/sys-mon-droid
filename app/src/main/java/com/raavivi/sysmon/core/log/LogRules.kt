package com.raavivi.sysmon.core.log

import okhttp3.HttpUrl
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure rules for log naming, retention, size cap, line format and URL redaction. */
object LogRules {
    const val PREFIX = "sysmon-"
    const val SUFFIX = ".log"
    const val RETENTION_MS = 24L * 60 * 60 * 1000
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val TRUNCATED_MARKER = "-- log truncated: file size cap reached, further lines dropped --"

    private val NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HH")
    private val PARSE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val LINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val NAME_RE = Regex("""sysmon-(\d{8}-\d{2})\.log""")

    /** Hourly file name for [nowMs] in [zone], e.g. `sysmon-20261109-15.log`. */
    fun fileName(nowMs: Long, zone: ZoneId): String =
        PREFIX + NAME_FMT.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + SUFFIX

    /** Hour-start (epoch ms) encoded in [name], or null when it does not parse. */
    fun hourStart(name: String, zone: ZoneId): Long? {
        val m = NAME_RE.matchEntire(name) ?: return null
        return try {
            LocalDateTime.parse(m.groupValues[1] + "0000", PARSE_FMT)
                .atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Is [name] one of this app's log files (matching the prefix)? */
    fun isLogName(name: String): Boolean = name.startsWith(PREFIX)

    /**
     * Names to delete at [nowMs]: parsed files whose hour-start is older than now-24h, plus
     * unparseable names that carry the `sysmon-` prefix. Other names are never touched.
     */
    fun toPrune(names: List<String>, nowMs: Long, zone: ZoneId): List<String> {
        val cutoff = nowMs - RETENTION_MS
        return names.filter { n ->
            if (!isLogName(n)) return@filter false
            val start = hourStart(n, zone)
            start == null || start < cutoff
        }
    }

    /** `2026-11-09T15:04:05.123+02:00 I/Tag: message` plus the stack trace when [trace] is set. */
    fun formatLine(nowMs: Long, zone: ZoneId, level: Char, tag: String, msg: String, trace: String?): String {
        val ts = LINE_FMT.format(Instant.ofEpochMilli(nowMs).atZone(zone))
        val sb = StringBuilder(ts).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
        if (!trace.isNullOrEmpty()) sb.append('\n').append(trace.trimEnd())
        return sb.append('\n').toString()
    }

    enum class Cap { WRITE, MARKER, DROP }

    /** Decides what to do with a [lineBytes] line on a file already [size] bytes long. */
    fun capDecision(size: Long, lineBytes: Int, markerWritten: Boolean, max: Long = MAX_FILE_BYTES): Cap = when {
        size + lineBytes <= max -> Cap.WRITE
        markerWritten -> Cap.DROP
        else -> Cap.MARKER
    }

    private val SENSITIVE = setOf("token", "access_token", "password", "code")

    /** Replaces the values of sensitive query params with `***`; keeps everything else as-is. */
    fun redactQuery(encodedQuery: String?): String? {
        if (encodedQuery.isNullOrEmpty()) return encodedQuery
        return encodedQuery.split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            if (key.lowercase() in SENSITIVE) "$key=***" else part
        }
    }

    /** `/path?query` with sensitive params redacted. Never includes host, headers or bodies. */
    fun redactedTarget(url: HttpUrl): String {
        val q = redactQuery(url.encodedQuery)
        return if (q.isNullOrEmpty()) url.encodedPath else url.encodedPath + "?" + q
    }
}
