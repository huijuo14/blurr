package com.blurr.voice.api

import android.graphics.Bitmap

interface LlmApi {
    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String? = null
    ): com.blurr.voice.v2.AgentOutput?
}
