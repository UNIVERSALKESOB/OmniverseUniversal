package com.omniverse.universal.data.agent.opensource

import com.omniverse.universal.data.agent.AgentChatBackend
import com.omniverse.universal.domain.model.AgentKind
import com.omniverse.universal.domain.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Free & Open-Source AI Agent Backend for Omniverse Universal.
 * Connects directly to local Ollama / llama.cpp or self-hosted open-source endpoints
 * without requiring subscriptions or paid API tokens.
 */
class OllamaChatBackend(
    val endpointUrl: String = "http://127.0.0.1:11434",
    val modelName: String = "qwen2.5-coder:7b"
) : AgentChatBackend {

    override val kind: AgentKind = AgentKind.OPEN_SOURCE

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(prompt: String, conversationId: String) = withContext(Dispatchers.IO) {
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            sender = "user",
            content = prompt,
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        val assistantMsgId = UUID.randomUUID().toString()
        val initialAssistantMsg = ChatMessage(
            id = assistantMsgId,
            sender = "assistant",
            content = "",
            timestamp = System.currentTimeMillis()
        )
        _messages.value = _messages.value + initialAssistantMsg

        try {
            val messagesArray = JSONArray().apply {
                _messages.value.dropLast(1).forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.sender)
                        put("content", msg.content)
                    })
                }
            }

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("stream", true)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$endpointUrl/api/chat")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
            var accumulatedText = ""

            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val json = JSONObject(line)
                        if (json.has("message")) {
                            val chunk = json.getJSONObject("message").optString("content", "")
                            accumulatedText += chunk
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(content = accumulatedText) else msg
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _messages.value = _messages.value.map { msg ->
                if (msg.id == assistantMsgId) {
                    msg.copy(content = "Error connecting to local open-source agent: ${e.message}\nEnsure `ollama serve` or `llama-server` is running in OmniTerminal on port 11434.")
                } else msg
            }
        }
    }
}
