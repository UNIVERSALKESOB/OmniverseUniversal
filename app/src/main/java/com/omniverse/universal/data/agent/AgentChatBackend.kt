package com.omniverse.universal.data.agent

import com.omniverse.universal.domain.model.AgentKind
import com.omniverse.universal.domain.model.ChatMessage
import com.omniverse.universal.domain.model.Conversation
import kotlinx.coroutines.flow.StateFlow

/** Chat lifecycle every agent backend (Antigravity, Claude Code) implements. */
interface AgentChatBackend {
    val kind: AgentKind
    val conversations: StateFlow<List<Conversation>>
    val currentConversation: StateFlow<Conversation?>
    val messages: StateFlow<List<ChatMessage>>
    val isStreaming: StateFlow<Boolean>
    val isLoadingConversation: StateFlow<Boolean>
    val conversationError: StateFlow<String?>

    fun startNewChat()
    fun selectConversation(id: String)
    fun sendMessage(content: String)
    fun stopStreaming()
    fun deleteConversation(id: String)
    fun forkConversation(id: String)
    fun retryConnections()
    fun onAppForegrounded()
}
