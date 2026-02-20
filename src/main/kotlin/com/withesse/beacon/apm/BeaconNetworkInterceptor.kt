package com.withesse.beacon.apm

import com.withesse.beacon.log.BLog
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp network interceptor — auto-logs HTTP requests with timing.
 * Auto-masks sensitive URL params (token/key/secret/password, etc.).
 * Custom params: BeaconNetworkInterceptor(sensitiveParams = setOf("token", "apiKey"))
 * Dependency: host app must include OkHttp (compileOnly).
 *
 * OkHttp 网络拦截器 — 自动记录 HTTP 请求日志和耗时。
 * 默认自动脱敏 URL 中含 token/key/secret/password 等参数。
 * 可通过构造函数自定义脱敏参数名。
 * 依赖: 宿主 App 需自行引入 OkHttp (compileOnly)。
 *
 * Usage | 用法:
 *   OkHttpClient.Builder()
 *       .addInterceptor(BeaconNetworkInterceptor())
 *       .build()
 */
class BeaconNetworkInterceptor(
    private val sensitiveParams: Set<String> = DEFAULT_SENSITIVE_PARAMS
) : Interceptor {

    companion object {
        private val DEFAULT_SENSITIVE_PARAMS = setOf(
            "token", "access_token", "refresh_token",
            "key", "api_key", "apikey",
            "secret", "password", "passwd",
            "authorization", "credential"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        val safeUrl = sanitizeUrl(request.url)
        val startMs = System.currentTimeMillis()

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startMs
            BLog.w("Net", "$method $safeUrl FAILED (${elapsed}ms) ${e.javaClass.simpleName}: ${e.message}")
            PerfLogger.record("http_error", mapOf(
                "method" to method,
                "url" to safeUrl,
                "duration_ms" to elapsed,
                "error" to (e.message ?: e.javaClass.simpleName)
            ))
            throw e
        }

        val elapsed = System.currentTimeMillis() - startMs
        val code = response.code
        val contentLength = response.body?.contentLength() ?: -1

        if (code in 200..299) {
            BLog.d("Net", "$method $safeUrl → $code (${elapsed}ms, ${formatSize(contentLength)})")
        } else {
            BLog.w("Net", "$method $safeUrl → $code (${elapsed}ms, ${formatSize(contentLength)})")
        }

        PerfLogger.record("http_request", mapOf(
            "method" to method,
            "url" to safeUrl,
            "code" to code,
            "duration_ms" to elapsed,
            "content_length" to contentLength
        ))

        return response
    }

    private fun sanitizeUrl(url: HttpUrl): String {
        if (url.querySize == 0) return url.toString()
        val builder = url.newBuilder()
        for (name in url.queryParameterNames) {
            if (sensitiveParams.any { name.equals(it, ignoreCase = true) }) {
                builder.setQueryParameter(name, "***")
            }
        }
        return builder.build().toString()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return "${"%.1f".format(bytes / 1024.0)}KB"
        return "${"%.1f".format(bytes / 1024.0 / 1024.0)}MB"
    }
}
