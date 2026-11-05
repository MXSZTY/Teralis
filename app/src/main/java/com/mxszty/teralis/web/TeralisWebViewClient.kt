package com.mxszty.teralis.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class TeralisWebViewClient(private val host: Host) : WebViewClient() {

    interface Host {
        /** 主框架开始加载（可在此更新地址栏、显示进度） */
        fun onMainFrameStarted(url: String)
        /** 主框架加载完成 */
        fun onMainFrameFinished(url: String)
        /** 主框架加载失败 */
        fun onMainFrameError(url: String, errorCode: Int, description: String?)
        /** 交由外部应用处理（非 http(s) 链接）；返回 true 表示已处理 */
        fun shouldOpenExternally(url: String): Boolean
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (!request.isForMainFrame) return false
        if (host.shouldOpenExternally(url)) return true
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        host.onMainFrameStarted(url)
        // 尽早注入，争取在页面自身脚本执行前完成劫持
        WebConfigurator.injectVirtualLocation(view)
        WebConfigurator.applyPageZoom(view)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        host.onMainFrameFinished(url)
        // 二次注入，覆盖重定向 / 异步文档场景
        WebConfigurator.injectVirtualLocation(view)
        WebConfigurator.applyPageZoom(view)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            host.onMainFrameError(
                request.url?.toString() ?: view.url ?: "",
                error.errorCode,
                error.description?.toString()
            )
        }
    }

    // 浏览器类应用常见取舍：用户可能访问自签名证书的内网设备，放行但不静默
    @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.proceed()
    }
}
