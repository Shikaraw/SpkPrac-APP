package com.spkprac.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat completions client used for translation and practice reports.
 *
 * Every instance shares one [OkHttpClient] (connection pool + dispatcher); only the endpoint, key and
 * read timeout differ. A new client per session used to leak a dispatcher thread each time.
 */
class TextApi(
    private val endpoint: String,
    private val apiKey: String,
    private val timeoutSec: Long = 60
) {

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient by lazy {
        if (timeoutSec == 60L) {
            sharedClient
        } else {
            sharedClient.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        }
    }

    suspend fun complete(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.3,
        maxTokens: Int = 2048
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${text.take(300)}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
            content ?: throw IllegalStateException("Empty response from model")
        }
    }
}
