package com.mxszty.teralis.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.mxszty.teralis.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查：查询 GitHub 仓库最新 Release，将 tag 中的版本号与当前版本比较。
 * 网络请求为同步阻塞实现，调用方需自行放在子线程执行。
 */
object UpdateChecker {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/MXSZTY/Teralis/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/MXSZTY/Teralis/releases"
    private const val TIMEOUT_MS = 8000
    private const val MAX_NOTES_LENGTH = 600

    sealed interface CheckResult {
        data class UpdateAvailable(
            val tag: String,
            val notes: String,
            val pageUrl: String
        ) : CheckResult

        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    /** GitHub Release JSON 的最小映射（Gson 按字段名直接匹配） */
    private data class ReleaseDto(
        val tag_name: String? = null,
        val body: String? = null,
        val html_url: String? = null
    )

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    fun check(currentVersion: String): CheckResult = runCatching {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub API 要求携带 User-Agent，否则返回 403
            setRequestProperty("User-Agent", "Teralis-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            // 仓库尚无 Release 时 latest 接口返回 404，语义上即“没有更新”
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return CheckResult.UpToDate
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return CheckResult.Failed
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val release = Gson().fromJson(json, ReleaseDto::class.java) ?: return CheckResult.Failed
            val tag = release.tag_name?.trim().orEmpty()
            if (tag.isEmpty()) return CheckResult.Failed
            if (isNewer(tag, currentVersion)) {
                CheckResult.UpdateAvailable(
                    tag = tag,
                    notes = release.body.orEmpty().trim().take(MAX_NOTES_LENGTH),
                    pageUrl = release.html_url ?: RELEASES_PAGE
                )
            } else {
                CheckResult.UpToDate
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(CheckResult.Failed)

    /** 逐段数字比较版本号，兼容 "v1.2.0" / "1.2" 写法；任一侧无法解析时视为无更新 */
    private fun isNewer(latestTag: String, current: String): Boolean {
        val latest = parseVersion(latestTag) ?: return false
        val cur = parseVersion(current) ?: return false
        val max = maxOf(latest.size, cur.size)
        for (i in 0 until max) {
            val l = latest.getOrElse(i) { 0 }
            val c = cur.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun parseVersion(tag: String): List<Int>? {
        val cleaned = tag.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isBlank()) return null
        return cleaned.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** 发现新版本时的统一对话框：展示版本号与 Release 说明，可跳转下载页 */
    fun showUpdateDialog(context: Context, info: CheckResult.UpdateAvailable) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_available_title, info.tag))
            .setMessage(
                info.notes.ifBlank { context.getString(R.string.update_no_notes) }
            )
            .setPositiveButton(R.string.update_download) { _, _ ->
                if (!WebUtils.openInBrowser(context, info.pageUrl)) {
                    WebUtils.toast(context, context.getString(R.string.main_no_external_browser))
                }
            }
            .setNegativeButton(R.string.common_later, null)
            .show()
    }
}
