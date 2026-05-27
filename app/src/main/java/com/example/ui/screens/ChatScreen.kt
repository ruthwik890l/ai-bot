package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.ChatMessage
import com.example.database.ChatSession
import com.example.repository.AiRepository
import com.example.ui.OpenMindViewModel
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import coil.compose.AsyncImage

@Composable
fun ChatScreen(
    viewModel: OpenMindViewModel,
    innerPadding: PaddingValues
) {
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()
    val currentMessages by viewModel.currentMessages.collectAsState()
    
    val uiStateMessage by viewModel.uiStateMessage.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val inferenceStatus by viewModel.inferenceStatus.collectAsState()
    
    val streamingResponse by viewModel.streamingResponse.collectAsState()
    val streamingCompareResponse by viewModel.streamingCompareResponse.collectAsState()
    val compareWeightLoad by viewModel.compareWeightLoad.collectAsState()
    
    val activeModel by viewModel.activeModel.collectAsState()
    val compareModel by viewModel.compareModel.collectAsState()
    val isCompareMode by viewModel.isCompareMode.collectAsState()
    
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsState()

    val currentSession = allSessions.find { it.id == currentSessionId }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll on new messages
    LaunchedEffect(currentMessages.size, streamingResponse, streamingCompareResponse) {
        if (currentMessages.isNotEmpty() || streamingResponse.isNotEmpty()) {
            listState.animateScrollToItem((currentMessages.size * 2).coerceAtLeast(0))
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        bottomBar = {
            val attachedImageUri by viewModel.attachedImageUri.collectAsState()
            ChatBottomInput(
                messageText = uiStateMessage,
                onMessageChange = { viewModel.setUiPromptMessage(it) },
                isGenerating = isGenerating,
                isRecordingVoice = isRecordingVoice,
                attachedImageUri = attachedImageUri,
                onAttachImage = { viewModel.selectAttachedImage(it) },
                onSend = {
                    val finalMsg = if (uiStateMessage.isBlank() && attachedImageUri != null) {
                        "Analyze and describe this attached photo."
                    } else {
                        uiStateMessage
                    }
                    viewModel.sendMessage(finalMsg)
                    viewModel.setUiPromptMessage("")
                },
                onVoiceToggle = { viewModel.startSimulatedVoiceSTT() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Info Bar
            HeaderInfoBar(
                currentSession = currentSession,
                activeModel = activeModel,
                compareModel = compareModel,
                isCompareMode = isCompareMode,
                isGenerating = isGenerating,
                inferenceStatus = inferenceStatus,
                compareWeightLoad = compareWeightLoad,
                onToggleCompare = { viewModel.toggleCompareMode(!isCompareMode) }
            )

            if (currentMessages.isEmpty() && streamingResponse.isEmpty()) {
                EmptyChatWelcome(activeModel, onSuggestionClick = { viewModel.setUiPromptMessage(it) })
            } else {
                if (isCompareMode) {
                    // COMPARATIVE MODE SPLIT VIEW
                    SplitCompareChatLayout(
                        messages = currentMessages,
                        streamingA = streamingResponse,
                        streamingB = streamingCompareResponse,
                        modelA = activeModel,
                        modelB = compareModel,
                        isGenerating = isGenerating,
                        onSpeak = { viewModel.triggerTtsAudioSpeak(it) }
                    )
                } else {
                    // STANDARD CHAT VIEW
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        items(currentMessages) { message ->
                            ChatMessageItem(
                                message = message,
                                onSpeak = { viewModel.triggerTtsAudioSpeak(message.content) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Stream reply placeholder
                        if (streamingResponse.isNotEmpty()) {
                            item {
                                StreamingResponseItem(
                                    streamingText = streamingResponse,
                                    modelId = activeModel,
                                    onSpeak = { viewModel.triggerTtsAudioSpeak(streamingResponse) }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderInfoBar(
    currentSession: ChatSession?,
    activeModel: String,
    compareModel: String,
    isCompareMode: Boolean,
    isGenerating: Boolean,
    inferenceStatus: String,
    compareWeightLoad: String,
    onToggleCompare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSession?.title ?: "OpenMind Assistant",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isCompareMode) "⚖️ Comparing: $activeModel vs $compareModel" else "🧠 Active Model: $activeModel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isGenerating) Color.Green else Color.Gray)
                        )
                    }
                }

                // Split Mode Switch
                Button(
                    onClick = onToggleCompare,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCompareMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp).testTag("compare_mode_toggle")
                ) {
                    Text(
                        text = if (isCompareMode) "Single View" else "Compare Split",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Real-time inference stats updates (Token speeds, CPU logs)
            AnimatedVisibility(visible = isGenerating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(
                        text = "⚡ Local LLM Engine Status:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Primary Stream: $inferenceStatus",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isCompareMode) {
                            Text(
                                text = "Compare Stream: $compareWeightLoad",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatWelcome(
    activeModel: String,
    onSuggestionClick: (String) -> Unit
) {
    val suggestions = listOf(
        "Write a quick sort algorithm in python",
        "Explain reinforcement learning step by step",
        "How is OpenMind secured completely offline?",
        "Configure custom agents for writing research papers"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🧠", fontSize = 42.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Welcome to OpenMind AI",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = "Zero internet. 100% private. All neural structures loaded offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Tap a suggested local system query:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { suggestion ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionClick(suggestion) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "💡  $suggestion",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onSpeak: () -> Unit
) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(text = if (message.modelUsed == "deepseek_r1") "🔮" else "🤖", fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 300.dp)
        ) {
            // Model Label for Assistant
            if (!isUser) {
                Text(
                    text = message.modelUsed.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                )
            }

            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 2.dp,
                    bottomEnd = if (isUser) 2.dp else 16.dp
                ),
                tonalElevation = if (isUser) 0.dp else 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Associated user uploaded photo if defined
                    if (!message.imageUri.isNullOrEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.04f))
                                .padding(bottom = 8.dp)
                        ) {
                            val isPreset = message.imageUri.startsWith("preset")
                            if (isPreset) {
                                val presetName = when {
                                    message.imageUri.contains("invoice") -> "Invoiced Billings Doc"
                                    message.imageUri.contains("chart") -> "System Trend Graphs"
                                    message.imageUri.contains("kitten") -> "Curious Ginger Cat Portrait"
                                    message.imageUri.contains("code") -> "Kotlin Compose Editor Layout"
                                    else -> "Brainstorm Technical Roadmap"
                                }
                                val presetColor = when {
                                    message.imageUri.contains("invoice") -> MaterialTheme.colorScheme.primaryContainer
                                    message.imageUri.contains("chart") -> MaterialTheme.colorScheme.secondaryContainer
                                    message.imageUri.contains("kitten") -> MaterialTheme.colorScheme.tertiaryContainer
                                    message.imageUri.contains("code") -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(presetColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = when {
                                                message.imageUri.contains("invoice") -> "📄"
                                                message.imageUri.contains("chart") -> "📈"
                                                message.imageUri.contains("kitten") -> "🐱"
                                                message.imageUri.contains("code") -> "💻"
                                                else -> "📝"
                                            },
                                            fontSize = 44.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = presetName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = "Unrestricted On-Device Vision Sandbox", fontSize = 8.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            } else {
                                AsyncImage(
                                    model = message.imageUri,
                                    contentDescription = "User uploaded photo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                        }
                    }

                    // DeepSeek-R1 Expandable Thinking UI
                    if (!isUser && !message.thought.isNullOrBlank()) {
                        ExpandableThoughtBlock(thought = message.thought)
                    }

                    // Render Message Content (Text & Code Blocks)
                    RenderMessageContent(
                        text = message.content,
                        isUser = isUser
                    )
                }
            }

            // Quick message utilities (TTS, Copy)
            if (!isUser) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🔊 Speak",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSpeak() }
                    )
                    Text(
                        text = "📋 Copy",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(message.content))
                        }
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "👨‍💻", fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun ExpandableThoughtBlock(thought: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🧠 ", fontSize = 12.sp)
                    Text(
                        text = "DeepSeek chain-of-thought log...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = if (expanded) "Collapse ▲" else "Expand ▼",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thought,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun RenderMessageContent(text: String, isUser: Boolean) {
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val clipboard = LocalClipboardManager.current

    // Look for code block sequences in content: ```kotlin ... ```
    val rawText = text
    val parts = rawText.split("```")

    if (parts.size <= 1) {
        // Plain Text
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    } else {
        // Rich Content: Render interleaved Text and Code blocks
        Column(modifier = Modifier.fillMaxWidth()) {
            for (i in parts.indices) {
                val segment = parts[i]
                if (i % 2 == 0) {
                    // Regular Text segment
                    if (segment.isNotBlank()) {
                        Text(
                            text = segment.trim(),
                            color = textColor,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    // Code block segment
                    val lines = segment.trim().split("\n")
                    val language = lines.firstOrNull() ?: "code"
                    val codeBody = if (lines.size > 1) lines.drop(1).joinToString("\n") else ""

                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = CardStrokeBorder()
                    ) {
                        Column {
                            // Code Block Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = language.uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "COPY",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .clickable { clipboard.setText(AnnotatedString(codeBody)) }
                                        .padding(4.dp)
                                )
                            }
                            // Code Box
                            Text(
                                text = codeBody,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun CardStrokeBorder(): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
fun StreamingResponseItem(
    streamingText: String,
    modelId: String,
    onSpeak: () -> Unit
) {
    // Process <think> blocks on the fly for active streaming responses
    val thinkRegex = Regex("<think>([\\s\\S]*?)</think>")
    val matchResult = thinkRegex.find(streamingText)
    
    val thoughtText = matchResult?.groups?.get(1)?.value?.trim() ?: ""
    val hasFinishedThought = streamingText.contains("</think>")
    
    val activeThoughtStream = if (streamingText.startsWith("<think>")) {
        if (hasFinishedThought) {
            thoughtText
        } else {
            streamingText.replace("<think>", "").trim()
        }
    } else {
        ""
    }

    val finalBodyStream = if (hasFinishedThought) {
        streamingText.replace(thinkRegex, "").trim()
    } else if (!streamingText.startsWith("<think>")) {
        streamingText
    } else {
        ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🔮", fontSize = 15.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f).widthIn(max = 300.dp)) {
            Text(
                text = "$modelId (STREAMS)",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 2.dp,
                    bottomEnd = 16.dp
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Active Streamed Thought
                    if (activeThoughtStream.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Thinking Log (${if (hasFinishedThought) "Finished" else "Analyzing..."}):",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = activeThoughtStream,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Active Body Stream
                    if (finalBodyStream.isNotEmpty()) {
                        RenderMessageContent(text = finalBodyStream, isUser = false)
                    } else if (activeThoughtStream.isEmpty()) {
                        // Tiny pulsing indicator
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            Text("●", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), fontSize = 12.sp)
                            Text("●", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.SplitCompareChatLayout(
    messages: List<ChatMessage>,
    streamingA: String,
    streamingB: String,
    modelA: String,
    modelB: String,
    isGenerating: Boolean,
    onSpeak: (String) -> Unit
) {
    // Separate primary model outputs from secondary comparison model outputs.
    // Secondary messages are prefixed in database with "[Model Comparison Secondary Stream - modelId]:"
    val userMessages = messages.filter { it.role == "user" }
    
    val modelAMessages = messages.filter { 
        it.role == "assistant" && !it.content.startsWith("[Model Comparison Secondary Stream")
    }
    
    val modelBMessages = messages.filter { 
        it.role == "assistant" && it.content.startsWith("[Model Comparison Secondary Stream")
    }

    Column(modifier = Modifier.fillMaxSize().weight(1f)) {
        // Last User Prompt Indicator
        val lastUserPrompt = userMessages.lastOrNull()?.content ?: "Awaiting system inputs..."
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
        ) {
            Text(
                text = "💬 Compare Prompt: \"$lastUserPrompt\"",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            // LEFT COLUMN - Model A
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "◀ PRIMARY: $modelA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(modelAMessages) { msg ->
                        SimpleCompareItem(message = msg, onSpeak = onSpeak)
                    }
                    if (streamingA.isNotEmpty()) {
                        item {
                            SimpleInferenceStreamItem(text = streamingA)
                        }
                    }
                }
            }

            // VERTICAL DIVIDER line
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )

            // RIGHT COLUMN - Model B
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "▶ COMPARE: $modelB",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(modelBMessages) { msg ->
                        // Clean prefix before displaying
                        val cleanText = msg.content.substringAfter("]:\n\n")
                        val cleanMsg = msg.copy(content = cleanText)
                        SimpleCompareItem(message = cleanMsg, onSpeak = onSpeak)
                    }
                    if (streamingB.isNotEmpty()) {
                        item {
                            SimpleInferenceStreamItem(text = streamingB)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleCompareItem(message: ChatMessage, onSpeak: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Expandable Thought block in side-by-side mode
            if (!message.thought.isNullOrBlank()) {
                ExpandableThoughtBlock(thought = message.thought)
            }
            RenderMessageContent(text = message.content, isUser = false)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "🔊 Speak",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSpeak(message.content) }
            )
        }
    }
}

@Composable
fun SimpleInferenceStreamItem(text: String) {
    // Strips thought tag if streaming
    val cleanText = if (text.startsWith("<think>")) {
        if (text.contains("</think>")) {
            text.substringAfter("</think>").trim()
        } else {
            "thinking..."
        }
    } else {
        text
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = cleanText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ChatBottomInput(
    messageText: String,
    onMessageChange: (String) -> Unit,
    isGenerating: Boolean,
    isRecordingVoice: Boolean,
    attachedImageUri: String?,
    onAttachImage: (String?) -> Unit,
    onSend: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    var showPhotoPickerDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onAttachImage(uri.toString())
        }
    }

    if (showPhotoPickerDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoPickerDialog = false },
            title = { Text("Unlimited Vision Upload Sandbox", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "OpenMind lets you process unlimited visual files and photos completely free on-device without subscription cap constraints.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text("Capture Methods:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                launcher.launch("image/*")
                                showPhotoPickerDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🖼️ Gallery", fontSize = 11.sp)
                        }
                        
                        Button(
                            onClick = {
                                val randId = (1000..9999).random()
                                onAttachImage("content://media/external/images/media/camera_${randId}")
                                showPhotoPickerDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("📷 Camera Snap", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Instant Mock Sandbox Presets:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    
                    val presets = listOf(
                        "preset_invoice" to "🧾 Billing Invoice / Business Receipt",
                        "preset_chart" to "📊 Metrics Analysis Trend Chart",
                        "preset_kitten" to "🐱 Curious Ginger Pet Kitten Portrait",
                        "preset_code" to "💻 Code Capture (Kotlin Compose Editor)",
                        "preset_note" to "🗒️ Handwritten Project Ideas Memo"
                    )
                    
                    presets.forEach { (presetUri, label) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAttachImage(presetUri)
                                    showPhotoPickerDialog = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPhotoPickerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("bottom_chat_input"),
        tonalElevation = 8.dp,
        border = CardStrokeBorder()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding()) {
            
            // Microphone wave pulsing
            AnimatedVisibility(visible = isRecordingVoice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🎙️ Speaking... Tap Mic again to generate transcript", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Wave simulation blobs
                    InfinitePulseWave()
                }
            }

            // Attached Image Preview
            AnimatedVisibility(visible = !attachedImageUri.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val isPreset = attachedImageUri?.startsWith("preset") == true
                        if (isPreset) {
                            Text(
                                text = when {
                                    attachedImageUri!!.contains("invoice") -> "📄"
                                    attachedImageUri.contains("chart") -> "📈"
                                    attachedImageUri.contains("kitten") -> "🐱"
                                    attachedImageUri.contains("code") -> "💻"
                                    else -> "📝"
                                },
                                fontSize = 24.sp
                            )
                        } else {
                            AsyncImage(
                                model = attachedImageUri,
                                contentDescription = "Attached Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Photo Ready to Send", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = when {
                                attachedImageUri?.startsWith("preset_invoice") == true -> "Sandbox: Invoice Receipt"
                                attachedImageUri?.startsWith("preset_chart") == true -> "Sandbox: Trend Metrics Chart"
                                attachedImageUri?.startsWith("preset_kitten") == true -> "Sandbox: Cute Kitten"
                                attachedImageUri?.startsWith("preset_code") == true -> "Sandbox: Kotlin Editor Code"
                                attachedImageUri?.startsWith("preset_note") == true -> "Sandbox: Handwritten Notes"
                                else -> "Local Device Gallery attachment"
                            },
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(
                        onClick = { onAttachImage(null) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("❌", fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Button
                IconButton(
                    onClick = onVoiceToggle,
                    modifier = Modifier
                        .background(
                            if (isRecordingVoice) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                        .testTag("voice_input_button")
                ) {
                    Text(text = "🎙️", fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Photos Attachment Button
                IconButton(
                    onClick = { showPhotoPickerDialog = true },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .testTag("attach_photo_button")
                ) {
                    Text(text = "🖼️", fontSize = 20.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Text Frame input
                val isSendEnabled = (messageText.isNotBlank() || !attachedImageUri.isNullOrEmpty()) && !isGenerating
                TextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_text_input"),
                    placeholder = { Text("Ask local vision/neural structure...", fontSize = 13.sp) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send Button
                IconButton(
                    onClick = onSend,
                    enabled = isSendEnabled,
                    modifier = Modifier
                        .background(
                            if (isSendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            CircleShape
                        )
                        .testTag("send_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (isSendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun InfinitePulseWave() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseRatio by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_ratio"
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size((8 * pulseRatio).dp).clip(CircleShape).background(Color.Red))
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.Red.copy(alpha = 0.5f)))
        Box(modifier = Modifier.size((12 / pulseRatio).dp).clip(CircleShape).background(Color.Red))
    }
}
