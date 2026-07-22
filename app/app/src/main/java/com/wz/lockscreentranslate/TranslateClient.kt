package com.wz.lockscreentranslate

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams a translation from the proxy over SSE. Mirrors the proxy's contract:
 *   data: {"type":"chunk","text":...}
 *   data: {"type":"done","content":...,"model":...,"citations":[...]}
 *   data: {"type":"error","error":...}
 * Call from a background thread. Callbacks fire on that thread.
 */
object TranslateClient {
    private const val TAG = "TranslateClient"

    fun stream(
        proxyUrl: String,
        authToken: String,
        input: String,
        render: String,
        web: Boolean = false,
        onChunk: (String) -> Unit,
        onDone: (content: String, model: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var conn: HttpURLConnection? = null
        try {
            val payload = JSONObject()
                .put("input", input)
                .put("mode", "everyday")
                .put("render", render)
                .put("web", web)          // opt-in forced web-grounding ("verify")
                .put("stream", true)
                .toString()
            Log.i(TAG, "POST $proxyUrl payload=$payload")
            conn = (URL(proxyUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                if (authToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $authToken")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() ?: ""
                onError("HTTP $code: ${err.take(300)}")
                return
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = try { JSONObject(line.substring(6)) } catch (e: Exception) { continue }
                    when (data.optString("type")) {
                        "chunk" -> onChunk(data.optString("text"))
                        "done" -> {
                            Log.i(TAG, "done model=${data.optString("model")} len=${data.optString("content").length}")
                            onDone(data.optString("content"), data.optString("model"))
                        }
                        "error" -> onError(data.optString("error"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stream error", e)
            onError("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
