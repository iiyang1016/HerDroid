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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herdroid.app.core.hermes.ChatMessage
import com.herdroid.app.core.hermes.HermesViewModel
import com.herdroid.app.core.hermes.RuntimeState
import com.herdroid.app.core.terminal.TerminalController
import kotlinx.coroutines.launch

private enum class WorkspaceTab(val label: String) {
    Chat("Chat"),
    Terminal("Terminal"),
    Browser("Browser"),
}

@Composable
fun HerDroidApp(viewModel: HermesViewModel = viewModel()) {
    var tab by remember { mutableStateOf(WorkspaceTab.Chat) }

    MaterialTheme {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    WorkspaceTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.label.take(1), fontWeight = FontWeight.Bold) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ProductHeader(tab)
                when (tab) {
                    WorkspaceTab.Chat -> ChatWorkspace(viewModel)
                    WorkspaceTab.Terminal -> TerminalWorkspace()
                    WorkspaceTab.Browser -> BrowserWorkspace()
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(tab: WorkspaceTab) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("HerDroid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Standalone Hermes workstation · ${tab.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ChatWorkspace(viewModel: HermesViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

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
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Local runtime · ${runtime.name}", fontWeight = FontWeight.SemiBold)
                Text(
                    if (configured) model else "Model provider not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onClear) { Text("Clear") }
            FilledTonalButton(onClick = onSettings) { Text("Provider") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyChatState(configured: Boolean, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (configured) {
                "Hermes is running locally inside HerDroid. Chat requests go directly from this app to your configured model provider; no Hermes gateway is required."
            } else {
                "Set a model provider once, then HerDroid runs the local agent directly. No Hermes gateway or external Termux session is required."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            Button(onClick = onSettings) { Text("Configure provider") }
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
        title = { Text("Local Hermes provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "OpenAI-compatible endpoint. OpenRouter is the default, but local/network providers can be used too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    "The API key is encrypted with Android Keystore before being stored on-device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = baseUrl.isNotBlank() && model.isNotBlank()) {
                Text("Save & start")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            tonalElevation = if (isUser) 2.dp else 0.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f),
        ) {
            SelectionContainer {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (isUser) "You" else "Hermes",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message.text.ifEmpty { if (message.streaming) "…" else "" })
                }
            }
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
        Text(
            "Runs inside HerDroid's app sandbox. The local agent can call the same shell tool.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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
