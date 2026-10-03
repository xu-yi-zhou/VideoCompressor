/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.screen

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xuyizhou.videocompressor.data.netdisk.NetdiskConfig
import com.xuyizhou.videocompressor.ui.viewmodel.CompressViewModel
import kotlinx.coroutines.launch

/**
 * 百度网盘 OAuth 授权页。
 *
 * 用 WebView 加载开放平台授权地址（redirect_uri=oob）：用户登录并确认后，
 * 百度把授权码展示在页面上，本页从 URL 参数或页面文本中提取 32 位十六进制授权码，
 * 经 [CompressViewModel.completeAuth] 换取 token。WebView 被百度风控拦截时，
 * 页面底部提供手动粘贴授权码的兜底入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetdiskAuthScreen(
    viewModel: CompressViewModel,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var exchanging by remember { mutableStateOf(false) }
    var exchanged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualCode by remember { mutableStateOf("") }

    val authUrl = if (NetdiskConfig.configured) {
        "https://openapi.baidu.com/oauth/2.0/authorize" +
            "?response_type=code&client_id=${NetdiskConfig.APP_KEY}" +
            "&redirect_uri=oob&scope=${NetdiskConfig.SCOPE}&display=mobile"
    } else null

    fun tryExchange(code: String) {
        if (exchanging || exchanged) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        exchanging = true
        error = null
        scope.launch {
            val ok = viewModel.completeAuth(trimmed)
            exchanging = false
            if (ok) {
                exchanged = true
                onDone()
            } else {
                error = "授权失败：请检查网络与 AppKey/SecretKey 配置后重试"
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("授权百度网盘", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text("取消") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            if (authUrl == null) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "尚未配置网盘应用凭据",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "请到百度网盘开放平台（pan.baidu.com/union）注册应用，将 AppKey / SecretKey 填入 NetdiskConfig.kt。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(560.dp),
                    factory = { context ->
                        createAuthWebView(context) { code -> tryExchange(code) }.apply {
                            loadUrl(authUrl)
                        }
                    }
                )

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                if (exchanging) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // 兜底：WebView 被风控拦截时手动粘贴授权码
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "登录页无法打开？可在浏览器中打开上述地址，将页面显示的授权码粘贴到下方：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("授权码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { tryExchange(manualCode) },
                        enabled = manualCode.isNotBlank() && !exchanging,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("用授权码登录")
                    }
                }
            }
        }
    }
}

/** 构造授权 WebView；[onCodeFound] 在提取到授权码时回调（主线程）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun createAuthWebView(
    context: android.content.Context,
    onCodeFound: (String) -> Unit
): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.d("NetdiskAuth", "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.d("NetdiskAuth", "onPageFinished: $url title=${view?.title}")
                // 优先取 URL 里的 code 参数
                val fromUrl = url?.let { u ->
                    kotlin.runCatching { android.net.Uri.parse(u).getQueryParameter("code") }.getOrNull()
                }
                if (!fromUrl.isNullOrBlank()) {
                    onCodeFound(fromUrl)
                    return
                }
                // 兜底：从 oob 页面文本中提取 32 位十六进制授权码
                evaluateJavascript(
                    "(function(){var m=document.body?document.body.innerText:'';var r=m.match(/[0-9a-fA-F]{32}/);return r?r[0]:'';})()"
                ) { result ->
                    val code = result?.trim('"') ?: ""
                    if (code.length == 32) onCodeFound(code)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                android.util.Log.e(
                    "NetdiskAuth",
                    "onReceivedError: ${request?.url} err=${error?.errorCode} desc=${error?.description}"
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                android.util.Log.e(
                    "NetdiskAuth",
                    "onReceivedHttpError: ${request?.url} status=${errorResponse?.statusCode}"
                )
            }
        }
    }
}
