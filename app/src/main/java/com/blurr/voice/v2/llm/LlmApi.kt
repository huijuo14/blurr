package com.blurr.voice.v2.llm

import com.blurr.voice.v2.AgentOutput

interface LlmApi {
    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput?
}
