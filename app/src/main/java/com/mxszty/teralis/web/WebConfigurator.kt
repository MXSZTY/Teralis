package com.mxszty.teralis.web

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import com.mxszty.teralis.data.ConfigStore

/** 统一的 WebView 配置入口：根据全局设置与当前配置文件调整 WebSettings。 */
object WebConfigurator {

    fun apply(webView: WebView) {
        val s = webView.settings
        val cfg = ConfigStore.settings

        s.javaScriptEnabled = cfg.javaScriptEnabled
        s.domStorageEnabled = cfg.domStorageEnabled
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.allowFileAccess = cfg.fileAccessEnabled
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadsImagesAutomatically = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        s.setSupportZoom(cfg.pinchZoomEnabled)
        s.builtInZoomControls = cfg.pinchZoomEnabled
        s.displayZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        applyUserAgent(webView)
        applyInitialPageScale(webView)
    }

    /** 新页面加载时使用的整体缩放比例（setInitialScale 仅对新加载文档生效） */
    fun applyInitialPageScale(webView: WebView) {
        val percent = ConfigStore.settings.pageZoomPercent.coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        webView.setInitialScale(if (percent == 100) 0 else percent)
    }

    /** 对当前已加载文档实时应用整体缩放，无需刷新页面 */
    fun applyPageZoom(webView: WebView) {
        val percent = ConfigStore.settings.pageZoomPercent.coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        val scale = if (percent == 100) "" else (percent / 100f).toBigDecimal().toPlainString()
        webView.evaluateJavascript(
            "(function(){try{document.documentElement.style.zoom='$scale';}catch(e){}})();",
            null
        )
    }

    const val MIN_PAGE_ZOOM = 50
    const val MAX_PAGE_ZOOM = 300

    /** 实时应用当前 UA 配置；系统默认 UA 传 null 恢复内置值 */
    fun applyUserAgent(webView: WebView) {
        val ua = ConfigStore.activeUa()
        webView.settings.userAgentString = if (ua == null || ua.systemDefault || ua.userAgent.isBlank()) {
            null
        } else {
            ua.userAgent
        }
    }

    /** 若虚拟位置开启且已选择坐标，则注入位置欺骗脚本 */
    fun injectVirtualLocation(webView: WebView) {
        val profile = ConfigStore.activeProfile()
        if (!profile.virtualLocationEnabled) return
        val loc = ConfigStore.activeLocation() ?: return
        webView.evaluateJavascript(
            LocationInjector.script(loc, ConfigStore.settings.deepLocationInject),
            null
        )
    }

    /** 清除网站全部浏览数据（Cookie / 缓存 / 存储） */
    fun clearBrowsingData(webView: WebView) {
        webView.stopLoading()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        webView.clearCache(true)
        webView.clearHistory()
        @Suppress("DEPRECATION")
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()
    }
}
