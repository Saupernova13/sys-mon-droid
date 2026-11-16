package com.raavivi.sysmon.core.tracker.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer

/**
 * Gzips POST bodies once the server has said it accepts them (RFC 7694: a response header
 * `Accept-Encoding` containing gzip), so an older server never sees a compressed body. A 415 clears
 * that and retries the request plain; after one, gzip stays off for this instance (the 415 itself
 * advertises what the server takes, which would otherwise switch it straight back on).
 */
class GzipRequestInterceptor : Interceptor {

    @Volatile private var advertised = false

    @Volatile private var rejected = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!advertised || rejected || !isGzipCandidate(req)) return observe(chain.proceed(req))
        val res = chain.proceed(gzipped(req))
        if (res.code != UNSUPPORTED) return observe(res)
        res.close()
        advertised = false
        rejected = true
        return chain.proceed(req)
    }

    private fun observe(res: Response): Response {
        if (res.header("Accept-Encoding")?.contains("gzip", ignoreCase = true) == true) advertised = true
        return res
    }

    private fun gzipped(req: Request): Request {
        val body = req.body!!
        val out = Buffer()
        GzipSink(out).buffer().use { body.writeTo(it) }
        return req.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(req.method, out.readByteString().toRequestBody(body.contentType()))
            .build()
    }

    companion object {
        const val UNSUPPORTED = 415

        /** Bodies this small grow when gzipped (header and trailer), so they go as they are. */
        const val MIN_BYTES = 128L

        fun isGzipCandidate(req: Request): Boolean {
            val body: RequestBody = req.body ?: return false
            return req.method == "POST" &&
                req.header("Content-Encoding") == null &&
                (body.contentLength() < 0 || body.contentLength() >= MIN_BYTES)
        }
    }
}
