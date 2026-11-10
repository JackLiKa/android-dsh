package com.example.adsh

import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun NativeDshWebView(
    tokenUrl: String,
    chooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    cancelFileChooser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The page lays itself out with `html, body, #root { height: 100% }`. Creating the
    // WebView before Compose assigns it a height leaves Blink's viewport at 0, so the
    // whole app renders with zero height. Wait for a measured height and pass it as an
    // explicit pixel height so Blink starts with the real viewport.
    var contentHeight by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val height = coordinates.size.height
                    if (height > 0 && height != contentHeight) contentHeight = height
                },
        ) {
            if (contentHeight > 0) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                contentHeight,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webViewClient = object : WebViewClient() {
                                private var retriedWithToken = false

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val address = request?.url ?: return true
                                    return address.scheme != "http" || address.host != "127.0.0.1" ||
                                        address.port != 3080
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame == true &&
                                        errorResponse?.statusCode == 401 &&
                                        !retriedWithToken
                                    ) {
                                        retriedWithToken = true
                                        view?.post { view.loadUrl(tokenUrl) }
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    callback: ValueCallback<Array<Uri>>?,
                                    params: FileChooserParams?,
                                ): Boolean {
                                    if (callback == null || params == null) return false
                                    return chooseFiles(callback, params)
                                }
                            }
                            val origin = "http://127.0.0.1:3080/"
                            val hasSession = CookieManager.getInstance().getCookie(origin)
                                ?.contains("dsh-auth-") == true
                            loadUrl(if (hasSession) origin else tokenUrl)
                        }
                    },
                    update = { view ->
                        val params = view.layoutParams
                        if (params.height != contentHeight) {
                            params.height = contentHeight
                            view.layoutParams = params
                        }
                    },
                    onRelease = { view ->
                        cancelFileChooser()
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}
