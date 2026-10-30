package com.omniverse.universal.data.agent.opensource

import com.omniverse.universal.data.agent.AgentChatBackend
import com.omniverse.universal.domain.model.AgentKind
import com.omniverse.universal.domain.model.ChatMessage
import com.omniverse.universal.domain.model.Conversation
import com.omniverse.universal.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var streamJob: Job? = null

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    override val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun startNewChat() {
        stopStreaming()
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "Open Source Chat",
            agent = AgentKind.OPEN_SOURCE
        )
        _currentConversation.value = newConv
        _conversations.value = listOf(newConv) + _conversations.value
        _messages.value = emptyList()
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id } ?: return
        stopStreaming()
        _currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
    }

    override fun sendMessage(content: String) {
        if (content.isBlank()) return
        val conv = _currentConversation.value ?: run {
            startNewChat()
            _currentConversation.value!!
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = MessageRole.USER,
            content = content
        )
        _messages.value = _messages.value + userMsg

        val assistantMsgId = UUID.randomUUID().toString()
        val initialAssistantMsg = ChatMessage(
            id = assistantMsgId,
            conversationId = conv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        _messages.value = _messages.value + initialAssistantMsg
        _isStreaming.value = true

        streamJob = scope.launch {
            try {
                val messagesArray = JSONArray().apply {
                    _messages.value.dropLast(1).forEach { msg ->
                        put(JSONObject().apply {
                            put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
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
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == assistantMsgId) msg.copy(isStreaming = false) else msg
                }
            } catch (e: Exception) {
                val errorMsg = "Error connecting to local open-source agent: ${e.message}\nEnsure `ollama serve` or `llama-server` is running in OmniTerminal on port 11434."
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == assistantMsgId) msg.copy(content = errorMsg, isStreaming = false) else msg
                }
                _conversationError.value = e.message
            } finally {
                _isStreaming.value = false
            }
        }
    }

    override fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        _isStreaming.value = false
        _messages.value = _messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
    }

    override fun deleteConversation(id: String) {
        if (_currentConversation.value?.id == id) {
            stopStreaming()
            _currentConversation.value = null
            _messages.value = emptyList()
        }
        _conversations.value = _conversations.value.filter { it.id != id }
    }

    override fun forkConversation(id: String) {
        val conv = _conversations.value.find { it.id == id } ?: return
        val forked = conv.copy(
            id = UUID.randomUUID().toString(),
            title = "${conv.title} (Fork)"
        )
        _conversations.value = listOf(forked) + _conversations.value
        selectConversation(forked.id)
    }

    override fun retryConnections() {
        // No-op for local HTTP client
    }

    override fun onAppForegrounded() {
        // No-op
    }
}
