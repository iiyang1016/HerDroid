package com.herdroid.app.ui.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.herdroid.app.core.runtime.BotModeController
import org.json.JSONObject

private const val RENDERER_INDEX = "hermes/index.html"
private const val RENDERER_READY = "hermes/herdroid-ready.json"
private const val RENDERER_URL = "https://appassets.androidplatform.net/assets/hermes/index.html"

fun hasHermesRenderer(context: Context): Boolean =
    assetExists(context, RENDERER_INDEX) && assetExists(context, RENDERER_READY)

private fun assetExists(context: Context, path: String): Boolean = runCatching {
    context.assets.open(path).use { true }
}.getOrDefault(false)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HermesRendererHost(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(context),
                )
                .build()

            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(HerDroidNativeBridge(context), "HerDroidNative")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = request?.url?.let(assetLoader::shouldInterceptRequest)
                }
                loadUrl(RENDERER_URL)
            }
        },
    )
}

private class HerDroidNativeBridge(context: Context) {
    private val appContext = context.applicationContext

    @JavascriptInterface
    fun invoke(payload: String): String {
        val request = runCatching { JSONObject(payload) }.getOrElse {
            return errorResponse("invalid_request", "Invalid bridge request").toString()
        }

        return runCatching {
            when (request.optString("method")) {
                "platform.ping" -> JSONObject()
                    .put("ok", true)
                    .put("platform", "android")
                "bot.get" -> JSONObject()
                    .put("ok", true)
                    .put("enabled", BotModeController.isEnabled(appContext))
                "bot.set" -> {
                    val enabled = request.optJSONObject("params")?.optBoolean("enabled") ?: false
                    BotModeController.setEnabled(appContext, enabled)
                    JSONObject().put("ok", true).put("enabled", enabled)
                }
                else -> errorResponse(
                    "not_implemented",
                    "Android bridge method is not implemented yet: ${request.optString("method")}",
                )
            }
        }.getOrElse { error ->
            errorResponse("bridge_error", error.message ?: "Android bridge call failed")
        }.toString()
    }

    private fun errorResponse(code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))
}
