package com.blurr.voice.api

import android.content.Context
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.SettingsActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenAiApi : LlmApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<android.graphics.Bitmap>,
        modelName: String?
    ): String? {
        val context = MyApplication.appContext
        val sharedPreferences = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
        val baseUrl = sharedPreferences.getString(SettingsActivity.KEY_CUSTOM_API_BASE_URL, "")
        val finalModelName = modelName ?: sharedPreferences.getString(SettingsActivity.KEY_CUSTOM_API_MODEL_NAME, "")
        val apiKey = sharedPreferences.getString(SettingsActivity.KEY_CUSTOM_API_KEY, "")

        if (baseUrl.isNullOrEmpty() || finalModelName.isNullOrEmpty() || apiKey.isNullOrEmpty()) {
            Log.e("OpenAiApi", "Custom API settings are not configured.")
            return null
        }

        val payload = buildPayload(chat, finalModelName)
        val request = Request.Builder()
            .url(baseUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    parseSuccessResponse(responseBody)
                } else {
                    Log.e("OpenAiApi", "API call failed with HTTP ${response.code}. Response: $responseBody")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("OpenAiApi", "API call failed with exception.", e)
            null
        }
    }

    private fun buildPayload(chat: List<Pair<String, List<Any>>>, modelName: String): JSONObject {
        val rootObject = JSONObject()
        rootObject.put("model", modelName)
        rootObject.put("stream", false)

        val messagesArray = JSONArray()
        chat.forEach { (role, parts) ->
            parts.forEach { part ->
                if (part is com.google.ai.client.generativeai.type.TextPart) {
                    val messageObject = JSONObject()
                    messageObject.put("role", role.lowercase())
                    messageObject.put("content", part.text)
                    messagesArray.put(messageObject)
                }
            }
        }
        rootObject.put("messages", messagesArray)
        return rootObject
    }

    private fun parseSuccessResponse(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                message.getString("content")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("OpenAiApi", "Failed to parse successful response: $responseBody", e)
            null
        }
    }
}
