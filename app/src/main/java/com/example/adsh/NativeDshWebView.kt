package com.example.adsh

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun NativeDshWebView(
    tokenUrl: String,
    chooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    cancelFileChooser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    private var retriedWithToken = false

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val address = request?.url ?: return true
                        return address.scheme != "http" || address.host != "127.0.0.1" ||
                            address.port != 3080
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?) {
                        if (request?.isForMainFrame == true && errorResponse?.statusCode == 401 &&
                            !retriedWithToken) {
                            retriedWithToken = true
                            view?.post { view.loadUrl(tokenUrl) }
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        params: FileChooserParams?): Boolean {
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
        onRelease = { view ->
            cancelFileChooser()
            view.stopLoading()
            view.destroy()
        },
    )
}
