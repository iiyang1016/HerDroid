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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.herdroid.app.core.hermes.ChatMessage
import com.herdroid.app.core.hermes.GatewayConnectionState
import com.herdroid.app.core.hermes.HermesViewModel
import com.herdroid.app.core.terminal.TerminalController
import kotlinx.coroutines.launch

private enum class WorkspaceTab(val label: String) { Chat("Chat"), Terminal("Terminal"), Browser("Browser") }

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
        Text("Hermes workstation for Android · ${tab.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun ChatWorkspace(viewModel: HermesViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()?.text) {
        if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        ConnectionStrip(ui.endpoint, ui.token, connection, viewModel::updateEndpoint, viewModel::updateToken, viewModel::connect, viewModel::disconnect)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.messages.isEmpty()) item {
                Text("Connect to a Hermes gateway, then start a session. Local embedded runtime comes next; this transport already uses Hermes' native JSON-RPC contract.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(ui.messages, key = { it.id }) { MessageBubble(it) }
        }
        ui.status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        ui.error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = ui.composer, onValueChange = viewModel::updateComposer, modifier = Modifier.weight(1f), placeholder = { Text("Ask Hermes…") }, maxLines = 5)
            Button(onClick = viewModel::send, enabled = connection == GatewayConnectionState.Open && ui.composer.isNotBlank(), modifier = Modifier.height(56.dp)) { Text("Send") }
        }
    }
}

@Composable
private fun ConnectionStrip(
    endpoint: String, token: String, state: GatewayConnectionState,
    onEndpoint: (String) -> Unit, onToken: (String) -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = endpoint, onValueChange = onEndpoint, modifier = Modifier.fillMaxWidth(), label = { Text("Hermes gateway") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = token, onValueChange = onToken, modifier = Modifier.weight(1f), label = { Text("Token (optional)") }, singleLine = true)
            Button(onClick = if (state == GatewayConnectionState.Open) onDisconnect else onConnect, modifier = Modifier.height(56.dp)) {
                Text(if (state == GatewayConnectionState.Open) "Disconnect" else "Connect")
            }
        }
        Text("Gateway: ${state.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(tonalElevation = if (isUser) 2.dp else 0.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f)) {
            SelectionContainer {
                Column(Modifier.padding(12.dp)) {
                    Text(if (isUser) "You" else "Hermes", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
            Text(transcript, modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101114)).padding(12.dp), color = Color(0xFFE7E7E7), fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Command") })
            Button(onClick = {
                val current = command
                running = true
                transcript += "\n$ $current\n"
                scope.launch {
                    val result = controller.execute(current)
                    if (result.output.isNotEmpty()) transcript += result.output + "\n"
                    transcript += "[exit ${result.exitCode}]\n"
                    running = false
                }
            }, enabled = !running && command.isNotBlank(), modifier = Modifier.height(56.dp)) { Text(if (running) "Running" else "Run") }
        }
        Text("Runs inside HerDroid's sandbox. No Termux dependency. PTY + packaged toolchain are Phase 2.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWorkspace() {
    var address by remember { mutableStateOf("https://www.google.com") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Address") })
            Button(onClick = {
                val normalized = if (address.startsWith("http://") || address.startsWith("https://")) address else "https://$address"
                address = normalized
                webView?.loadUrl(normalized)
            }, modifier = Modifier.height(56.dp)) { Text("Go") }
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
