package com.omniverse.universal

import androidx.compose.runtime.Composable
import com.omniverse.universal.ui.chat.ChatScreen
import com.omniverse.universal.ui.chat.ChatViewModel

@Composable
fun MainNavigation(viewModel: ChatViewModel? = null) {
    if (viewModel != null) {
        ChatScreen(viewModel = viewModel)
    } else {
        ChatScreen()
    }
}
