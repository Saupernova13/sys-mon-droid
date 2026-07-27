package com.raavivi.sysmon

import com.raavivi.sysmon.core.auth.TokenRenewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The renewal window is what keeps the home-screen widgets alive: the server
 * issues 72-hour tokens, and nothing but this decides when to trade one in. Get
 * it wrong in one direction and the widgets die three days after the last app
 * visit; wrong in the other and every widget tick spends a needless request.
 */
class TokenRenewerTest {

    private fun jwt(exp: Long?): String {
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val claims = if (exp == null) {
            """{"sub":"admin","role":"admin"}"""
        } else {
            """{"sub":"admin","role":"admin","iat":1,"jti":"abc","exp":$exp}"""
        }
        return "$header.${encode(claims)}.signature-not-checked-here"
    }

    /** Base64url without padding, exactly as a JWT carries it. */
    private fun encode(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun readsTheExpiryClaimOffThePayload() {
        assertEquals(1_800_000_000L, TokenRenewer.expiresAtSeconds(jwt(1_800_000_000L)))
    }

    @Test
    fun unreadableTokensYieldNoExpiry() {
        assertNull(TokenRenewer.expiresAtSeconds(null))
        assertNull(TokenRenewer.expiresAtSeconds(""))
        assertNull(TokenRenewer.expiresAtSeconds("not-a-jwt"))
        // Well-formed shape, but the payload isn't JSON we can read.
        assertNull(TokenRenewer.expiresAtSeconds("a.!!!not-base64!!!.c"))
        // Valid JSON with no exp claim.
        assertNull(TokenRenewer.expiresAtSeconds(jwt(null)))
    }

    @Test
    fun freshTokensAreLeftAlone() {
        val now = 1_800_000_000L
        // A token just minted with the server's 72-hour TTL.
        val token = jwt(now + 72 * 3600)
        assertFalse(TokenRenewer.isDue(token, now))
        // Still not due with just over a day left.
        assertFalse(TokenRenewer.isDue(jwt(now + 25 * 3600), now))
    }

    @Test
    fun tokensInsideTheLastDayAreRenewed() {
        val now = 1_800_000_000L
        assertTrue(TokenRenewer.isDue(jwt(now + 23 * 3600), now))
        assertTrue(TokenRenewer.isDue(jwt(now + 60), now))
        // Already expired: still "due", so a widget waking up late at least
        // tries rather than giving up without a request.
        assertTrue(TokenRenewer.isDue(jwt(now - 3600), now))
    }

    @Test
    fun anUnreadableTokenIsTreatedAsDue() {
        // Spending one request beats letting a token we can't read quietly
        // strand every widget.
        assertTrue(TokenRenewer.isDue("garbage", 1_800_000_000L))
    }

    @Test
    fun theRenewalWindowLeavesRoomForManyAttempts() {
        // Widgets update on a 30-minute floor, so a 24-hour window is ~48
        // chances to renew before a 72-hour token lapses.
        assertEquals(24L * 60 * 60, TokenRenewer.RENEW_WITHIN_SECONDS)
        assertTrue(TokenRenewer.RENEW_WITHIN_SECONDS > 4 * 1800)
    }
}
