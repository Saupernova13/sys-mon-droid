package com.raavivi.sysmon.core.auth

import android.util.Log
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import java.util.Base64

/**
 * Keeps the stored JWT from ageing out under an unattended client.
 *
 * The server issues tokens with a 72-hour TTL and nothing used to renew them,
 * so the session died three days after the app was last opened. That is fine for
 * a screen you visit; it is fatal for the home-screen plug widgets, which are
 * expected to keep working precisely when nobody is opening the app.
 *
 * Renewal is opportunistic: every widget refresh (and every app launch) calls
 * [renewIfDue], which only spends a request when the token is inside its last
 * [RENEW_WITHIN_SECONDS]. At a 30-minute widget cadence that leaves dozens of
 * chances to renew before expiry.
 */
class TokenRenewer(
    private val settings: SettingsStore,
    private val api: ApiProvider,
) {
    /**
     * Renew if the current token is close to expiring. Returns true when a fresh
     * token was stored. Failures are swallowed: the existing token is still
     * valid for a while, and an offline phone must not lose its session just
     * because one renewal attempt could not reach the server.
     */
    suspend fun renewIfDue(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val token = api.token ?: return false
        if (!isDue(token, nowSeconds)) return false
        return when (val r = safeCall { api.api.refreshToken() }) {
            is ApiResult.Ok -> {
                api.token = r.value.token
                settings.setToken(r.value.token)
                true
            }
            is ApiResult.Err -> {
                // A server that predates /auth/refresh answers 404 — expected
                // against an un-redeployed host, not worth shouting about.
                if (r.code != 404) Log.w(TAG, "token renewal failed: ${r.message}")
                false
            }
        }
    }

    companion object {
        private const val TAG = "TokenRenewer"

        /** Renew once the token has under a day left of its 72-hour life. */
        const val RENEW_WITHIN_SECONDS = 24L * 60 * 60

        /**
         * Whether [token] is inside its renewal window. A token whose expiry
         * can't be read is treated as due: better to spend one request than to
         * let an unreadable token quietly strand the widgets.
         */
        fun isDue(token: String, nowSeconds: Long): Boolean {
            val exp = expiresAtSeconds(token) ?: return true
            return exp - nowSeconds < RENEW_WITHIN_SECONDS
        }

        /**
         * The `exp` claim, read straight off the JWT payload. Decoding locally
         * avoids a round trip just to ask how long we have left, and the value is
         * only ever used to decide when to renew — the server remains the
         * authority on whether a token is actually valid.
         */
        fun expiresAtSeconds(token: String?): Long? {
            val payload = token?.split('.')?.getOrNull(1) ?: return null
            return try {
                val json = String(Base64.getUrlDecoder().decode(payload))
                // Small enough to pick out directly; pulling in a JSON parser for
                // one integer claim would be heavier than the claim itself.
                EXP.find(json)?.groupValues?.get(1)?.toLong()
            } catch (_: Exception) {
                null
            }
        }

        private val EXP = Regex("\"exp\"\\s*:\\s*(\\d+)")
    }
}
