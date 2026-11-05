package com.mxszty.teralis.web

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mxszty.teralis.R
import java.net.URL

class TeralisChromeClient(
    private val activity: Activity,
    private val host: Host
) : WebChromeClient() {

    interface Host {
        fun onLoadProgress(progress: Int)
        fun onPageTitleReceived(title: String?)
        fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams)
        fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback)
        fun onWebPermissionRequest(request: PermissionRequest)
        fun onShowFullscreen(view: View, callback: CustomViewCallback)
        fun onHideFullscreen()
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.onLoadProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        host.onPageTitleReceived(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        host.onFileChooser(filePathCallback, fileChooserParams)
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback
    ) {
        host.onGeolocationPrompt(origin ?: "", callback)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onWebPermissionRequest(request)
    }

    // ---------- 全屏视频 ----------

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.onShowFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.onHideFullscreen()
    }

    // ---------- JS 原生对话框（默认实现会静默丢弃，这里补齐为 Material 对话框） ----------

    private fun dialogTitle(url: String): String =
        runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.app_name)

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (activity.isFinishing) return false
        MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle(url))
            .setMessage(message)
            .setPositiveButton(R.string.common_confirm) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (activity.isFinishing) return false
        MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle(url))
            .setMessage(message)
            .setPositiveButton(R.string.common_confirm) { _, _ -> result.confirm() }
            .setNegativeButton(R.string.common_cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult
    ): Boolean {
        if (activity.isFinishing) return false
        val input = TextInputEditText(activity).apply {
            setText(defaultValue.orEmpty())
            setSelection(text?.length ?: 0)
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle(url))
            .setMessage(message)
            .setView(container)
            .setPositiveButton(R.string.common_confirm) { _, _ -> result.confirm(input.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.common_cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Log.d(
            "TeralisConsole",
            "${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
        )
        return true
    }
}
