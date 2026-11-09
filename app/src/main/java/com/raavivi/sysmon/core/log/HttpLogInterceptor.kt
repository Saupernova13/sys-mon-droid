package com.raavivi.sysmon.core.log

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Logs `METHOD /path?query status durationMs` per call. Never logs headers, bodies or the
 * values of sensitive query params (see [LogRules.redactQuery]).
 */
class HttpLogInterceptor(private val tag: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val target = "${req.method} ${LogRules.redactedTarget(req.url)}"
        val start = System.nanoTime()
        try {
            val res = chain.proceed(req)
            val ms = (System.nanoTime() - start) / 1_000_000
            val line = "$target ${res.code} ${ms}ms"
            if (res.code >= 400) AppLog.w(tag, line) else AppLog.i(tag, line)
            return res
        } catch (e: IOException) {
            val ms = (System.nanoTime() - start) / 1_000_000
            AppLog.w(tag, "$target FAILED ${ms}ms ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}
