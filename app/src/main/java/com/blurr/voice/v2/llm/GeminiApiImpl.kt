package com.blurr.voice.v2.llm

import android.content.Context
import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.SettingsActivity
import com.blurr.voice.utilities.ApiKeyManager
import com.blurr.voice.v2.AgentOutput
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class GeminiApiImpl(
    private val context: Context,
    private val modelName: String,
    private val apiKeyManager: ApiKeyManager, // Injected dependency
    private val maxRetry: Int = 3
) : LlmApi {

    companion object {
        private const val TAG = "GeminiV2Api"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()

    private val jsonGenerationConfig = GenerationConfig.builder().apply {
        responseMimeType = "application/json"
    }.build()

    private val requestOptions = RequestOptions(timeout = 60.seconds)

    override suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performApiCall(messages)
        } ?: return null

        return try {
            Log.d(TAG, "Parsing guaranteed JSON response. $jsonString")
            Log.d("GEMINIAPITEMP_OUTPUT", jsonString)
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON into AgentOutput. Error: ${e.message}", e)
            null
        }
    }

    private suspend fun performApiCall(messages: List<GeminiMessage>): String {
        val sharedPreferences = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        val apiSelection = sharedPreferences.getString(SettingsActivity.KEY_API_SELECTION, "default")

        return if (apiSelection == "proxy") {
            Log.i(TAG, "Proxy config found. Using secure Cloud Function.")
            performProxyApiCall(messages)
        } else {
            Log.i(TAG, "Proxy config not found. Using direct Gemini SDK call (Fallback).")
            performDirectApiCall(messages)
        }
    }

    private suspend fun performProxyApiCall(messages: List<GeminiMessage>): String {
        val sharedPreferences = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        val proxyUrl = sharedPreferences.getString(SettingsActivity.KEY_PROXY_URL, BuildConfig.GCLOUD_PROXY_URL) ?: BuildConfig.GCLOUD_PROXY_URL
        val proxyKey = sharedPreferences.getString(SettingsActivity.KEY_PROXY_API_KEY, BuildConfig.GCLOUD_PROXY_URL_KEY) ?: BuildConfig.GCLOUD_PROXY_URL_KEY
        val modelName = sharedPreferences.getString(SettingsActivity.KEY_PROXY_MODEL_NAME, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"

        val openaiMessages = messages.map {
            OpenAiRequestMessage(
                role = it.role.name.lowercase(),
                content = it.parts.filterIsInstance<TextPart>().joinToString("") { part -> part.text }
            )
        }
        val requestPayload = OpenAiRequestBody(modelName, openaiMessages)
        val jsonBody = jsonParser.encodeToString(OpenAiRequestBody.serializer(), requestPayload)

        val request = Request.Builder()
            .url(proxyUrl)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $proxyKey")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBodyString = response.body?.string()
            if (!response.isSuccessful || responseBodyString.isNullOrBlank()) {
                val errorMsg = "Proxy API call failed with code: ${response.code}, body: $responseBodyString"
                Log.e(TAG, errorMsg)
                throw IOException(errorMsg)
            }
            Log.d(TAG, "Successfully received response from proxy.")
            val openAiResponse = jsonParser.decodeFromString<OpenAiResponseBody>(responseBodyString)
            val agentOutput = AgentOutput(response = openAiResponse.output.first().content.first().text)
            return jsonParser.encodeToString(AgentOutput.serializer(), agentOutput)
        }
    }

    private suspend fun performDirectApiCall(messages: List<GeminiMessage>): String {
        val apiKey = apiKeyManager.getNextKey()
        val generativeModel = modelCache.getOrPut(apiKey) {
            Log.d(TAG, "Creating new GenerativeModel instance for key ending in ...${apiKey.takeLast(4)}")
            GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = jsonGenerationConfig,
                requestOptions = requestOptions
            )
        }
        val history = convertToSdkHistory(messages)
        val response = generativeModel.generateContent(*history.toTypedArray())
        response.text?.let {
            Log.d(TAG, "Successfully received response from model.")
            return it
        }
        val reason = response.promptFeedback?.blockReason?.name ?: "UNKNOWN"
        throw ContentBlockedException("Blocked or empty response from API. Reason: $reason")
    }

    private fun convertToSdkHistory(messages: List<GeminiMessage>): List<Content> {
        return messages.map { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "tool"
            }

            content(role) {
                message.parts.forEach { part ->
                    if (part is TextPart) {
                        text(part.text)
                        if(part.text.startsWith("<agent_history>") || part.text.startsWith("Memory:")) {
                            Log.d("GEMINIAPITEMP_INPUT", part.text)
                        }
                    }
                }
            }
        }
    }
}

@Serializable
private data class OpenAiRequestMessage(val role: String, val content: String)

@Serializable
private data class OpenAiRequestBody(val model: String, val messages: List<OpenAiRequestMessage>)

@Serializable
private data class OpenAiResponseBody(val output: List<OpenAiOutput>)

@Serializable
private data class OpenAiOutput(val content: List<OpenAiContent>)

@Serializable
private data class OpenAiContent(val text: String)

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1000L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}", e)
            if (attempt == times - 1) {
                Log.e("RetryUtil", "All $times retry attempts failed.")
                return null
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}