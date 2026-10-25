package com.acme.sso.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
    val error: String get() = json?.optString("error").orEmpty()
    val errorDescription: String get() = json?.optString("error_description").orEmpty()
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** Tiny blocking HTTP client; call from a background dispatcher. */
object Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
    ): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(status, text, conn.headerFields.filterKeys { it != null })
    }

    fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
}
