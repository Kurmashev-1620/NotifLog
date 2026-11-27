package com.kurmashev.moneta.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GoogleApiException(val code: Int, message: String) : Exception(message)

/** Минимальный HTTP-клиент для Google API (без тяжёлых библиотек) */
object Http {

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun json(method: String, url: String, token: String, body: Any? = null): JSONObject? =
        request(method, url, token, body?.toString()?.toByteArray(), "application/json; charset=utf-8")
            .let { if (it.isBlank()) null else JSONObject(it) }

    suspend fun request(
        method: String, url: String, token: String, body: ByteArray?, contentType: String?
    ): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code >= 400) throw GoogleApiException(code, "HTTP $code: ${text.take(300)}")
            text
        } finally {
            c.disconnect()
        }
    }
}
