package com.raavivi.sysmon

import com.raavivi.sysmon.core.tracker.model.Fix
import com.raavivi.sysmon.core.tracker.net.GzipRequestInterceptor
import com.raavivi.sysmon.core.tracker.track.rounded
import com.raavivi.sysmon.core.tracker.track.roundTo
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val json = "application/json".toMediaType()

/** Fix rounding and the gzip request interceptor (driven through a canned terminal interceptor, no network). */
class TrackerTrafficTest {

    // Rounding ---------------------------------------------------------------

    @Test
    fun roundsSensorFieldsButNotPosition() {
        val f = Fix(
            ts = 1_700_000_000.123,
            lat = 9.647000312805176,
            lon = -3.5,
            accuracyM = 12.34999942779541,
            altitudeM = 101.26000213623047,
            speedMps = 1.2345,
            bearingDeg = 271.04998779296875,
        ).rounded()
        assertEquals(1_700_000_000.123, f.ts, 0.0)
        assertEquals(9.647000312805176, f.lat, 0.0)
        assertEquals(12.3, f.accuracyM!!, 0.0)
        assertEquals(101.3, f.altitudeM!!, 0.0)
        assertEquals(1.23, f.speedMps!!, 0.0)
        assertEquals(271.0, f.bearingDeg!!, 0.0)
    }

    @Test
    fun nullsStayNull() {
        val f = Fix(ts = 1.0, lat = 0.0, lon = 0.0).rounded()
        assertNull(f.accuracyM)
        assertNull(f.bearingDeg)
        assertEquals(0.5, roundTo(0.4999, 1), 0.0)
    }

    // Gzip -------------------------------------------------------------------

    private val big = """{"fixes":[${"""{"ts":1.0,"lat":9.647,"lon":-3.5},""".repeat(20)}]}"""

    /** What the "server" saw, and what it answers with. */
    private class Wire(val respond: (Request) -> Pair<Int, String?>) {
        val seen = mutableListOf<Request>()
        val bodies = mutableListOf<ByteArray>()

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(GzipRequestInterceptor())
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                seen += req
                bodies += req.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
                val (code, accept) = respond(req)
                Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .apply { if (accept != null) header("Accept-Encoding", accept) }
                    .body("{}".toResponseBody(json))
                    .build()
            })
            .build()
    }

    private fun post(client: OkHttpClient, body: String) =
        client.newCall(Request.Builder().url("http://h/x").post(body.toRequestBody(json)).build()).execute().close()

    private fun gunzip(b: ByteArray): String =
        GzipSource(Buffer().write(b)).buffer().readUtf8()

    @Test
    fun plainUntilAdvertisedThenGzip() {
        val wire = Wire { 200 to "gzip" }
        post(wire.client, big)
        assertNull(wire.seen[0].header("Content-Encoding"))
        assertEquals(big, String(wire.bodies[0]))

        post(wire.client, big)
        assertEquals("gzip", wire.seen[1].header("Content-Encoding"))
        assertEquals(big, gunzip(wire.bodies[1]))
        assertEquals(wire.seen[0].body!!.contentType(), wire.seen[1].body!!.contentType())
    }

    @Test
    fun neverGzipWithoutAdvertisement() {
        val wire = Wire { 200 to null }
        repeat(3) { post(wire.client, big) }
        wire.seen.forEach { assertNull(it.header("Content-Encoding")) }
    }

    @Test
    fun otherEncodingsDoNotAdvertiseGzip() {
        val wire = Wire { 200 to "br, deflate" }
        repeat(2) { post(wire.client, big) }
        wire.seen.forEach { assertNull(it.header("Content-Encoding")) }
    }

    @Test
    fun smallBodiesAndGetsStayPlain() {
        val wire = Wire { 200 to "gzip" }
        post(wire.client, big) // learn
        post(wire.client, """{"a":1}""")
        wire.client.newCall(Request.Builder().url("http://h/x").build()).execute().close()
        assertNull(wire.seen[1].header("Content-Encoding"))
        assertNull(wire.seen[2].header("Content-Encoding"))
    }

    @Test
    fun fallsBackPlainOn415AndStaysOff() {
        val wire = Wire { req ->
            if (req.header("Content-Encoding") == "gzip") 415 to "gzip" else 200 to "gzip"
        }
        post(wire.client, big) // learns gzip
        post(wire.client, big) // sent gzip, 415, retried plain
        assertEquals(3, wire.seen.size)
        assertEquals("gzip", wire.seen[1].header("Content-Encoding"))
        assertNull(wire.seen[2].header("Content-Encoding"))
        assertEquals(big, String(wire.bodies[2]))

        post(wire.client, big) // plain, even though responses keep advertising gzip
        assertEquals(4, wire.seen.size)
        assertNull(wire.seen[3].header("Content-Encoding"))
    }
}
