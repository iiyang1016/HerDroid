package com.herdroid.app.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herdroid.app.core.hermes.ChatMessage
import com.herdroid.app.core.hermes.HermesViewModel
import com.herdroid.app.core.hermes.RuntimeState
import com.herdroid.app.core.terminal.TerminalController
import kotlinx.coroutines.launch

private enum class ShellPage(val label: String) {
    Chat("Chat"),
    Skills("Skills"),
    Messaging("Messaging"),
    Artifacts("Artifacts"),
}

private enum class WorkspacePane(val label: String) {
    Terminal("Terminal"),
    Browser("Browser"),
}

@Composable
fun HerDroidApp(viewModel: HermesViewModel = viewModel()) {
    var page by remember { mutableStateOf(ShellPage.Chat) }

    MaterialTheme {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    ShellPage.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Text(item.label.take(1), fontWeight = FontWeight.Bold) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ProductHeader(page)
                when (page) {
                    ShellPage.Chat -> ChatWorkspace(viewModel)
                    ShellPage.Skills -> PlaceholderPage(
                        title = "Skills",
                        body = "Hermes skills will live here. The Android port will use the same skills surface and installed-skill model as Hermes Desktop.",
                    )
                    ShellPage.Messaging -> MessagingWorkspace(viewModel)
                    ShellPage.Artifacts -> PlaceholderPage(
                        title = "Artifacts",
                        body = "Artifacts produced by Hermes will appear here. This destination mirrors the durable Artifacts surface in Hermes Desktop.",
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(page: ShellPage) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("HerDroid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                page.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Local",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ChatWorkspace(viewModel: HermesViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var workspacePane by remember { mutableStateOf<WorkspacePane?>(null) }

    if (ui.providerSettingsOpen) {
        ProviderSettingsDialog(
            baseUrl = ui.provider.baseUrl,
            model = ui.provider.model,
            apiKey = ui.provider.apiKey,
            maxIterations = ui.provider.maxIterations,
            onBaseUrl = viewModel::updateProviderBaseUrl,
            onModel = viewModel::updateProviderModel,
            onApiKey = viewModel::updateProviderApiKey,
            onMaxIterations = viewModel::updateProviderMaxIterations,
            onSave = viewModel::saveProvider,
            onDismiss = viewModel::closeProviderSettings,
        )
    }

    workspacePane?.let { pane ->
        WorkspaceDialog(pane = pane, onDismiss = { workspacePane = null })
    }

    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()?.text) {
        if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        RuntimeStrip(
            runtime = runtime,
            model = ui.provider.model,
            configured = ui.provider.isConfigured,
            onSettings = viewModel::openProviderSettings,
            onClear = viewModel::clearConversation,
            onTerminal = { workspacePane = WorkspacePane.Terminal },
            onBrowser = { workspacePane = WorkspacePane.Browser },
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.messages.isEmpty()) {
                item {
                    EmptyChatState(
                        configured = ui.provider.isConfigured,
                        onSettings = viewModel::openProviderSettings,
                    )
                }
            }
            items(ui.messages, key = { it.id }) { MessageBubble(it) }
        }

        ui.status?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.error?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = ui.composer,
                onValueChange = viewModel::updateComposer,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Hermes…") },
                maxLines = 5,
                enabled = runtime != RuntimeState.Busy,
            )
            Button(
                onClick = viewModel::send,
                enabled = runtime == RuntimeState.Ready && ui.composer.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (runtime == RuntimeState.Busy) "Working" else "Send")
            }
        }
    }
}

@Composable
private fun RuntimeStrip(
    runtime: RuntimeState,
    model: String,
    configured: Boolean,
    onSettings: () -> Unit,
    onClear: () -> Unit,
    onTerminal: () -> Unit,
    onBrowser: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("${runtime.name} · ${if (configured) model else "No model"}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onTerminal) { Text("Terminal") }
            TextButton(onClick = onBrowser) { Text("Browser") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClear) { Text("Clear") }
            TextButton(onClick = onSettings) { Text("Provider") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun MessagingWorkspace(viewModel: HermesViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Messaging", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Run Hermes as an always-on bot from this phone. The foreground runtime stays local to HerDroid and will host the same messaging adapters and per-chat sessions as Hermes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Bot Mode", fontWeight = FontWeight.Medium)
                    Text(
                        if (ui.botModeEnabled) "Active · ${runtime.name}" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ui.botModeEnabled,
                    onCheckedChange = viewModel::setBotModeEnabled,
                )
            }
        }
        item { HorizontalDivider() }
        item {
            Text("Platforms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Telegram and Discord are the first Android adapters. Platform credentials, connection state, pairing requests, and approved users will use this surface as they are wired to the embedded Hermes messaging core.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ui.provider.isConfigured) {
            item {
                TextButton(onClick = viewModel::openProviderSettings) { Text("Configure model provider") }
            }
        }
    }
}

@Composable
private fun PlaceholderPage(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceDialog(pane: WorkspacePane, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(pane.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                when (pane) {
                    WorkspacePane.Terminal -> TerminalWorkspace()
                    WorkspacePane.Browser -> BrowserWorkspace()
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(configured: Boolean, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (configured) {
                "Hermes is ready locally."
            } else {
                "Configure a model provider to start Hermes."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "The agent runs inside HerDroid; no external Hermes gateway is required.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            TextButton(onClick = onSettings) { Text("Configure provider") }
        }
    }
}

@Composable
private fun ProviderSettingsDialog(
    baseUrl: String,
    model: String,
    apiKey: String,
    maxIterations: Int,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onMaxIterations: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrl,
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    label = { Text("Model") },
                    placeholder = { Text("provider/model-name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxIterations.toString(),
                    onValueChange = onMaxIterations,
                    label = { Text("Agent steps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "API keys are encrypted with Android Keystore on-device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = baseUrl.isNotBlank() && model.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (isUser) "You" else "Hermes",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(message.text.ifEmpty { if (message.streaming) "…" else "" })
        }
    }
}

@Composable
private fun TerminalWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { TerminalController(context.filesDir) }
    var command by remember { mutableStateOf("pwd") }
    var transcript by remember { mutableStateOf("HerDroid shell · /system/bin/sh\n") }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        SelectionContainer {
            Text(
                transcript,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF101114))
                    .padding(12.dp),
                color = Color(0xFFE7E7E7),
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Command") },
            )
            Button(
                onClick = {
                    val current = command
                    running = true
                    transcript += "\n$ $current\n"
                    scope.launch {
                        val result = controller.execute(current)
                        if (result.output.isNotEmpty()) transcript += result.output + "\n"
                        transcript += "[exit ${result.exitCode}]\n"
                        running = false
                    }
                },
                enabled = !running && command.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (running) "Running" else "Run")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWorkspace() {
    var address by remember { mutableStateOf("https://www.google.com") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Address") },
            )
            Button(
                onClick = {
                    val normalized = if (address.startsWith("http://") || address.startsWith("https://")) {
                        address
                    } else {
                        "https://$address"
                    }
                    address = normalized
                    webView?.loadUrl(normalized)
                },
                modifier = Modifier.height(56.dp),
            ) {
                Text("Go")
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl(address)
                    webView = this
                }
            },
            update = { webView = it },
        )
    }
}
