package com.mink.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices
import com.mink.guardian.ChatMessage
import com.mink.guardian.ChatRole
import com.mink.guardian.GuardianTier
import com.mink.guardian.ModelStatus
import com.mink.ui.nav.ChatPrefill
import kotlinx.coroutines.flow.launchIn

/**
 * The chat with Mink. Renders the guardian's persisted chat log as bubbles,
 * with each reply's private thinking hidden behind a collapsible toggle, and
 * streams new replies from [com.mink.guardian.Guardian.chat].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianChatScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    val guardian = services.guardian

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk to Mink") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (guardian == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Mink cannot chat on this device right now.", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val log by guardian.chatLog.collectAsStateWithLifecycle()
        val state by guardian.state.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        var draft by remember { mutableStateOf("") }

        LaunchedEffect(log.size) {
            if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
        }

        // A finding card can hand us a grounded question to open with. Consume it
        // once so a rotation does not replay the prefill, and never overwrite
        // anything the user has already typed.
        LaunchedEffect(Unit) {
            val pending = ChatPrefill.draft.value
            if (!pending.isNullOrEmpty() && draft.isEmpty()) draft = pending
            ChatPrefill.consume()
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (log.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Ask Mink what an app can see, or what a reading means.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(log, key = { it.id }) { message -> MessageBubble(message) }
                }
            }

            ModelStatusStrip(
                tier = state.tier,
                status = state.model.status,
                onDownload = { guardian.prepareModel() },
            )

            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Mink") },
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(0.dp))
                    IconButton(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                draft = ""
                                // Collecting the flow drives generation; the log
                                // updates itself as tokens stream in. Launch on the
                                // process-wide app scope, not the composition scope,
                                // so navigating back mid-reply does not cancel
                                // generation and strand a half-streamed message.
                                guardian.chat(text).launchIn(services.appScope)
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * A slim strip above the input that tells the user what is answering. It nudges a
 * one-tap download only when there is no usable model (absent, failed, or
 * unsupported); while the model downloads, verifies, loads, or sits ready it
 * simply says so; when a model is loaded it notes the on-device model; on a
 * rules-only device it says so plainly. Never alarming — the guardian answers
 * from rules either way.
 */
@Composable
private fun ModelStatusStrip(
    tier: GuardianTier,
    status: ModelStatus,
    onDownload: () -> Unit,
) {
    when {
        tier == GuardianTier.RULES_ONLY -> {
            Text(
                "This device runs the rules guardian.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        status == ModelStatus.LOADED -> {
            Text(
                "On-device model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        status == ModelStatus.DOWNLOADING ||
            status == ModelStatus.VERIFYING ||
            status == ModelStatus.LOADING -> {
            Text(
                "Mink is preparing its on-device model.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        status == ModelStatus.READY -> {
            Text(
                "Mink's model is downloaded. Enable the guardian to load it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        else -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Mink is answering from rules. Download the on-device model for real conversations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (!message.thinking.isNullOrBlank()) {
            ThinkingBlock(message.thinking!!)
            Spacer(Modifier.height(4.dp))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bubbleColor,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = message.content.ifBlank { if (message.streaming) "..." else "" },
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String) {
    var open by remember(thinking) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Thinking",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                thinking,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}
