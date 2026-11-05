package com.mxszty.teralis.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.mxszty.teralis.data.SearchEngine

object WebUtils {

    /** 将用户输入补全为合法 http(s) 地址；非法返回 null */
    fun normalizeUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("about:") -> raw
            raw.contains("://") -> raw // 自定义 scheme 不处理
            else -> "https://$raw"
        }
        return runCatching {
            val uri = withScheme.toUri()
            if (uri.scheme == null || uri.host.isNullOrBlank() && !withScheme.startsWith("about:")) null
            else withScheme
        }.getOrNull()
    }

    /**
     * 地址栏输入解析：输入像网址时返回规范化后的网址，否则用 [engine] 拼接搜索结果页地址。
     * 空输入返回 null。
     */
    fun resolveAddressInput(input: String, engine: SearchEngine): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val looksLikeUrl = raw.contains("://") || raw.startsWith("about:") ||
            (!raw.contains(' ') && (raw.contains('.') || raw.startsWith("localhost", true)))
        if (looksLikeUrl) return normalizeUrl(raw)
        return engine.template + Uri.encode(raw)
    }

    /** 尝试用外部应用处理非 http(s) 链接（mailto / tel / intent / geo 等） */
    fun launchExternal(context: Context, url: String): Boolean {
        return runCatching {
            val uri = url.toUri()
            when (uri.scheme?.lowercase()) {
                "http", "https", "about" -> false
                "intent" -> {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val resolved = intent.resolveActivity(context.packageManager) != null
                        if (resolved) context.startActivity(intent)
                        resolved
                    } else false
                }
                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val resolved = intent.resolveActivity(context.packageManager) != null
                    if (resolved) context.startActivity(intent)
                    resolved
                }
            }
        }.getOrDefault(false)
    }

    /** 用系统默认浏览器打开 http(s) 网址；没有可处理的浏览器时返回 false */
    fun openInBrowser(context: Context, url: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun toast(context: Context, message: String, long: Boolean = false) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
