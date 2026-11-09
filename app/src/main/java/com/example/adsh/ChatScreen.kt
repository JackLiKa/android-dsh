package com.example.adsh

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.adsh.engine.DshApproval
import com.example.adsh.engine.DshChatMessage
import com.example.adsh.engine.DshChatState
import com.example.adsh.engine.DshCommandInfo
import com.example.adsh.engine.DshModelCatalog
import com.example.adsh.engine.DshModelOption
import com.example.adsh.engine.DshQuestion
import com.example.adsh.engine.DshQuestionRequest
import com.example.adsh.engine.DshSessionSummary
import com.example.adsh.engine.DshStreaming
import com.example.adsh.engine.DshToolCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LONG_PRESS_TALK_MS = 350L

@Composable
fun ChatScreen(
    state: DshChatState,
    catalog: DshModelCatalog?,
    engineStatus: EngineStatus,
    initialPrompt: String = "",
    voiceRequest: Int = 0,
    speechLanguage: String = "zh-CN",
    speechEngine: String = "auto",
    onVoiceRequestHandled: () -> Unit = {},
    onStartEngine: () -> Unit,
    onRetryEngine: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onNewSession: () -> Unit,
    onSelectModel: (DshModelOption, String?) -> Unit,
    onApproval: (DshApproval, String) -> Unit,
    onAnswer: (DshQuestionRequest, List<Map<String, Any?>>) -> Unit,
    onRejectQuestion: (DshQuestionRequest) -> Unit,
    onDismissError: () -> Unit,
    onOpenLogs: () -> Unit = {},
) {
    var prompt by remember { mutableStateOf(initialPrompt) }
    var autoSent by rememberSaveable(initialPrompt) { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var queued by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val itemCount = state.messages.size +
        (if (state.optimisticUser != null) 1 else 0) +
        (if (state.streaming != null) 1 else 0)

    LaunchedEffect(itemCount, state.streaming?.text?.length, state.optimisticUser) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    LaunchedEffect(initialPrompt, state.engineReady) {
        if (!autoSent && initialPrompt.isNotBlank() && state.engineReady) {
            autoSent = true
            prompt = ""
            onSend(initialPrompt)
        }
    }

    LaunchedEffect(state.running) {
        if (!state.running) {
            queued?.let { text ->
                queued = null
                onSend(text)
            }
        }
    }

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val voice = remember(context, speechLanguage, speechEngine) {
        VoiceInputController(context, { speechLanguage }, { speechEngine })
    }
    val speaker = remember(context) { ReplySpeaker(context) }
    var listening by remember { mutableStateOf(false) }
    var voiceLevel by remember { mutableStateOf(0f) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var speakReplies by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf<String?>(null) }
    // Text the composer held before dictation, and the last text we wrote into it.
    var voiceSeed by remember { mutableStateOf("") }
    var voiceWritten by remember { mutableStateOf<String?>(null) }
    var voiceSuggestion by remember { mutableStateOf<String?>(null) }

    // Never clobber text the user edited while speaking; offer the final result instead.
    val applyVoiceText: (String, Boolean) -> Unit = { text, final ->
        val merged = if (voiceSeed.isBlank()) text else "$voiceSeed $text"
        if (prompt == voiceWritten) {
            prompt = merged
            voiceWritten = merged
            voiceSuggestion = null
        } else if (final) {
            voiceSuggestion = text
        }
    }

    val fallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
        } else {
            ""
        }
        if (text.isNotEmpty()) {
            voiceSeed = prompt.trim()
            voiceWritten = prompt
            applyVoiceText(text, true)
        }
    }

    val beginRecognition: () -> Unit = {
        voiceSuggestion = null
        if (voice.available()) {
            voiceSeed = prompt.trim()
            voiceWritten = prompt
            voice.start()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    if (speechLanguage == "zh-HK") "zh-HK" else "zh-CN",
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_input))
            }
            try {
                fallbackLauncher.launch(intent)
            } catch (error: Exception) {
                voiceError = context.getString(R.string.voice_unavailable)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginRecognition()
        } else {
            voiceError = context.getString(R.string.voice_permission_needed)
        }
    }

    val beginVoice: () -> Unit = {
        voiceError = null
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecognition()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(voiceRequest) {
        if (voiceRequest > 0) {
            onVoiceRequestHandled()
            beginVoice()
        }
    }

    DisposableEffect(voice) {
        voice.onListeningChanged = { active ->
            listening = active
            if (!active) voiceLevel = 0f
        }
        voice.onLevel = { level -> voiceLevel = level }
        voice.onResult = { result -> applyVoiceText(result.text, result.isFinal) }
        voice.onError = { message -> voiceError = message }
        onDispose {
            voice.release()
            speaker.release()
        }
    }

    val lastAssistant = state.messages.lastOrNull { !it.isUser }
    LaunchedEffect(speakReplies, lastAssistant?.key, state.running) {
        val message = lastAssistant
        if (
            speakReplies && !state.running && message != null &&
            !message.isError && message.text.isNotBlank() && message.key != lastSpoken
        ) {
            lastSpoken = message.key
            speaker.speak(message.text)
        }
    }

    if (showModels) {
        ModelPickerDialog(
            catalog = catalog,
            currentProvider = state.provider,
            currentModel = state.model,
            currentEffort = state.effort,
            onDismiss = { showModels = false },
            onPick = { option, effort ->
                showModels = false
                onSelectModel(option, effort)
            },
        )
    }

    state.questions.firstOrNull()?.let { request ->
        QuestionDialog(
            request = request,
            onAnswer = { answers -> onAnswer(request, answers) },
            onReject = { onRejectQuestion(request) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        speakReplies = !speakReplies
                        if (!speakReplies) speaker.stop()
                    },
                ) {
                    Icon(
                        painter = painterResource(
                            if (speakReplies) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
                        ),
                        contentDescription = stringResource(
                            if (speakReplies) R.string.speak_reply_on else R.string.speak_reply_off,
                        ),
                        tint = if (speakReplies) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    queued = null
                    onNewSession()
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_session))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.connection,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.engineReady) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { showModels = true },
                    enabled = catalog != null,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.engineReady) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.selectedLabel(catalog),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (itemCount == 0 && state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (itemCount == 0) {
                EmptyChat(
                    engineStatus = engineStatus,
                    state = state,
                    onStartEngine = onStartEngine,
                    onRetryEngine = onRetryEngine,
                    onOpenLogs = onOpenLogs,
                    onSuggestion = onSend,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.key }) { message ->
                        MessageBubble(message)
                    }
                    state.optimisticUser?.let { text ->
                        item(key = "optimistic") { UserBubble(text) }
                    }
                    state.streaming?.let { stream ->
                        item(key = "streaming") { StreamingBubble(stream) }
                    }
                }
            }
        }
        state.error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        state.approvals.firstOrNull()?.let { approval ->
            ApprovalCard(
                approval = approval,
                onAllow = { onApproval(approval, "allowed-once") },
                onReject = { onApproval(approval, "rejected") },
            )
        }
        voiceError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = { voiceError = null }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        Composer(
            value = prompt,
            onValueChange = { prompt = it },
            enabled = state.engineReady,
            running = state.running,
            onSend = {
                val text = prompt.trim()
                if (text.isNotEmpty()) {
                    prompt = ""
                    if (state.running) queued = text else onSend(text)
                }
            },
            onStop = onCancel,
            listening = listening,
            voiceLevel = voiceLevel,
            onVoiceTap = {
                if (listening) {
                    voice.stop()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.voice_hold_hint),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onVoiceStart = {
                if (!listening && state.engineReady) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    beginVoice()
                }
            },
            onVoiceStop = { voice.stop() },
            voiceSuggestion = voiceSuggestion,
            onApplyVoiceSuggestion = {
                voiceSuggestion?.let { text ->
                    prompt = if (voiceSeed.isBlank()) text else "$voiceSeed $text"
                    voiceWritten = prompt
                    voiceSuggestion = null
                }
            },
            queuedText = queued,
            onCancelQueued = { queued = null },
        )
    }
}

@Composable
private fun EmptyChat(
    engineStatus: EngineStatus,
    state: DshChatState,
    onStartEngine: () -> Unit,
    onRetryEngine: () -> Unit,
    onOpenLogs: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.engineReady) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val statusTitle = when (engineStatus.phase) {
                        EngineStatus.Phase.STARTING -> stringResource(R.string.engine_starting)
                        EngineStatus.Phase.FAILED -> stringResource(R.string.engine_failed)
                        EngineStatus.Phase.IDLE,
                        EngineStatus.Phase.STOPPED -> stringResource(R.string.engine_idle)
                        else -> state.connection
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.connecting || engineStatus.phase == EngineStatus.Phase.STARTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            statusTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (engineStatus.message.isNotBlank() && engineStatus.message != statusTitle) {
                        Text(
                            engineStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (engineStatus.phase) {
                        EngineStatus.Phase.IDLE, EngineStatus.Phase.STOPPED -> Button(onClick = onStartEngine) {
                            Text(stringResource(R.string.engine_start))
                        }
                        EngineStatus.Phase.FAILED -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = onRetryEngine) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.engine_retry))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onOpenLogs) {
                                Text(stringResource(R.string.open_engine_logs))
                            }
                        }
                        else -> Unit
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "深",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (state.engineReady) R.string.chat_empty_hint else R.string.empty_engine_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.engineReady) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.chat_suggestions_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        R.string.suggestion_tools,
                        R.string.suggestion_screen,
                        R.string.suggestion_notifications,
                    ).forEach { label ->
                        val suggestion = stringResource(label)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestion(suggestion) },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    suggestion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: DshChatMessage) {
    message.command?.let { command ->
        CommandBubble(command)
        return
    }
    if (message.isUser) {
        UserBubble(message.text)
        return
    }
    var showReasoning by remember(message.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (message.reasoning.isNotBlank()) {
                    Text(
                        if (showReasoning) stringResource(R.string.hide_reasoning)
                        else stringResource(R.string.show_reasoning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showReasoning = !showReasoning },
                    )
                    if (showReasoning) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (message.text.isNotBlank()) {
                    MarkdownText(message.text, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(2.dp))
                    MessageActions(message.text)
                } else if (message.tools.isEmpty()) {
                    Text(
                        stringResource(R.string.assistant_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (message.interrupted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.turn_interrupted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        message.tools.forEach { tool -> ToolCard(tool) }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MessageActions(text: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(
                    context,
                    context.getString(R.string.message_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_content_copy),
                contentDescription = stringResource(R.string.copy_message),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.copy_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share_message)),
                )
            },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.share_message),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.share_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommandBubble(command: DshCommandInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (command.failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                command.line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            command.result?.let { result ->
                Spacer(Modifier.height(4.dp))
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (command.failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(stream: DshStreaming) {
    var seconds by remember(stream.attemptId) { mutableStateOf(0) }
    LaunchedEffect(stream.attemptId) {
        while (true) {
            delay(1000)
            seconds += 1
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (stream.reasoning.isNotBlank()) {
                Text(
                    stringResource(R.string.streaming_reasoning_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stream.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (stream.text.isBlank()) 8 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (stream.text.isNotBlank()) {
                MarkdownText(stream.text, modifier = Modifier.fillMaxWidth())
                Text(
                    "▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (stream.reasoning.isNotBlank()) R.string.streaming_composing
                            else R.string.thinking,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.streaming_elapsed, seconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (stream.text.isNotBlank()) {
                    Text(
                        stringResource(R.string.streaming_chars, stream.text.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun ToolCard(tool: DshToolCall) {
    var expanded by remember(tool.callId) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (tool.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.name.ifBlank { "tool" },
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        tool.running -> stringResource(R.string.tool_running)
                        tool.isError -> stringResource(R.string.tool_failed)
                        else -> stringResource(R.string.tool_done)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        tool.isError -> MaterialTheme.colorScheme.error
                        tool.running -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (tool.arguments.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tool.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) 30 else 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tool.output?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) 60 else 6,
                    overflow = TextOverflow.Ellipsis,
                    color = if (tool.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (tool.arguments.length > 200 || (tool.output?.length ?: 0) > 400) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        stringResource(if (expanded) R.string.collapse else R.string.expand),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: DshApproval,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.approval_tool, approval.toolName),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            approval.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(4.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.approval_allow_once)) }
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.approval_reject)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    listening: Boolean,
    voiceLevel: Float,
    onVoiceTap: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    voiceSuggestion: String?,
    onApplyVoiceSuggestion: () -> Unit,
    queuedText: String?,
    onCancelQueued: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (listening) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VoiceLevelIndicator(voiceLevel)
                    Text(
                        stringResource(R.string.voice_listening),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.voice_listen_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            voiceSuggestion?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.voice_suggestion, text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onApplyVoiceSuggestion) {
                            Text(stringResource(R.string.voice_apply))
                        }
                    }
                }
            }
            queuedText?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.queued_message),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = onCancelQueued) {
                            Text(stringResource(R.string.queued_cancel))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (listening) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .pointerInput(enabled) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val released = withTimeoutOrNull(LONG_PRESS_TALK_MS) {
                                    waitForUpOrCancellation()
                                }
                                if (released == null && enabled) {
                                    onVoiceStart()
                                    waitForUpOrCancellation()
                                    onVoiceStop()
                                } else if (released != null) {
                                    onVoiceTap()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (listening) R.drawable.ic_mic_off else R.drawable.ic_mic,
                        ),
                        contentDescription = stringResource(
                            if (listening) R.string.voice_stop else R.string.voice_input,
                        ),
                        tint = if (listening) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.composer_hint)) },
                    maxLines = 5,
                    enabled = enabled,
                    shape = RoundedCornerShape(24.dp),
                )
                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.stop),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (running) R.string.composer_queue_hint else R.string.composer_scope,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceLevelIndicator(level: Float, modifier: Modifier = Modifier) {
    val weights = listOf(0.55f, 0.85f, 1f, 0.7f, 0.95f, 0.6f, 0.8f, 0.5f)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        weights.forEachIndexed { index, weight ->
            val target = 0.16f + level.coerceIn(0f, 1f) * weight * 0.84f
            val height by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 90),
                label = "voice-level-$index",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height((24f * height).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun QuestionDialog(
    request: DshQuestionRequest,
    onAnswer: (List<Map<String, Any?>>) -> Unit,
    onReject: () -> Unit,
) {
    val selections = remember(request.eventId) {
        mutableStateMapOf<String, Set<String>>()
    }
    val customs = remember(request.eventId) {
        mutableStateMapOf<String, String>()
    }
    val complete = request.questions.all { question ->
        selections[question.id].orEmpty().isNotEmpty() ||
            customs[question.id].orEmpty().isNotBlank()
    }
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    request.questions.firstOrNull()?.header ?: stringResource(R.string.question_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    request.questions.forEach { question ->
                        QuestionBlock(
                            question = question,
                            selected = selections[question.id].orEmpty(),
                            custom = customs[question.id].orEmpty(),
                            onToggle = { label ->
                                val current = selections[question.id].orEmpty()
                                selections[question.id] = when {
                                    question.multiSelect && label in current -> current - label
                                    question.multiSelect -> current + label
                                    label in current -> emptySet()
                                    else -> setOf(label)
                                }
                            },
                            onCustomChange = { customs[question.id] = it },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onReject) { Text(stringResource(R.string.skip)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onAnswer(
                                request.questions.map { question ->
                                    val selected = selections[question.id].orEmpty().toList()
                                    val custom = customs[question.id]?.trim().orEmpty()
                                    buildMap<String, Any?> {
                                        put("id", question.id)
                                        put("selected", selected)
                                        if (custom.isNotEmpty()) put("custom", custom)
                                    }
                                },
                            )
                        },
                        enabled = complete,
                    ) { Text(stringResource(R.string.submit)) }
                }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: DshQuestion,
    selected: Set<String>,
    custom: String,
    onToggle: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question.question, style = MaterialTheme.typography.titleSmall)
        question.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        question.options.forEach { option ->
            val isSelected = option.label in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(option.label) },
                label = { Text(option.label) },
            )
            option.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = onCustomChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.question_custom)) },
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModelPickerDialog(
    catalog: DshModelCatalog?,
    currentProvider: String?,
    currentModel: String?,
    currentEffort: String?,
    onDismiss: () -> Unit,
    onPick: (DshModelOption, String?) -> Unit,
) {
    var pending by remember { mutableStateOf<DshModelOption?>(null) }
    var effort by remember(currentProvider, currentModel) {
        mutableStateOf(currentEffort ?: catalog?.defaultOption()?.defaultEffort)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.choose_model_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                catalog?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                    failures.forEach { failure ->
                        Text(
                            failure,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    catalog?.options.orEmpty().forEach { option ->
                        val active = option.model == currentModel && option.provider == currentProvider
                        val chosen = pending?.let { it.provider == option.provider && it.model == option.model } == true
                        Surface(
                            color = when {
                                chosen -> MaterialTheme.colorScheme.secondaryContainer
                                active -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    if (option.efforts.isEmpty()) {
                                        onPick(option, null)
                                    } else {
                                        pending = option
                                        effort = option.defaultEffort
                                    }
                                },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(option.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${option.providerName} · ${option.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (chosen && option.efforts.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        option.efforts.forEach { candidate ->
                                            FilterChip(
                                                selected = effort == candidate.id,
                                                onClick = { effort = candidate.id },
                                                label = { Text(candidate.name, fontSize = 12.sp) },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Button(onClick = { onPick(option, effort) }) {
                                        Text(stringResource(R.string.confirm))
                                    }
                                }
                            }
                        }
                    }
                }
                if (catalog == null) {
                    Text(
                        stringResource(R.string.model_catalog_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
fun EngineHistoryScreen(
    sessions: List<DshSessionSummary>,
    currentSessionId: String?,
    enabled: Boolean,
    engineReady: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onStartEngine: () -> Unit,
) {
    val visible = remember(sessions) { sessions.filterNot { it.blank && it.id != currentSessionId } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNew, enabled = enabled) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.new_session))
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        stringResource(
                            if (engineReady) R.string.history_empty else R.string.history_engine_off,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (engineReady) {
                        Text(
                            stringResource(R.string.history_new_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onNew) {
                            Text(stringResource(R.string.new_session))
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = onStartEngine) {
                            Text(stringResource(R.string.engine_start))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { session ->
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (session.id == currentSessionId) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onOpen(session.id) }
                                .padding(14.dp),
                        ) {
                            Text(
                                session.title.ifBlank { stringResource(R.string.chat_new_title) },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(session.updatedAt)))
                                    session.cwd?.let { append(" · ").append(it.substringAfterLast('/')) }
                                    if (session.running) append(" · ").append(stringResource(R.string.session_running))
                                    session.model?.let { append(" · ").append(it) }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
