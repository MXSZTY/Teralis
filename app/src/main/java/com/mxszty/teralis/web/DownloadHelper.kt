package com.mxszty.teralis.web

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mxszty.teralis.R
import com.mxszty.teralis.util.WebUtils

/**
 * 将网页下载请求交给系统 DownloadManager：
 * Android 10+ 直接写入公共 Download 目录；低版本若无存储权限则退回到应用私有外部目录。
 */
object DownloadHelper {

    fun enqueue(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): Boolean {
        return runCatching {
            require(url.isNotBlank())
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                ?.takeIf { it.isNotBlank() }
                ?: "download_${System.currentTimeMillis()}"

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(url.toUri()).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(name)

                val publicAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                if (publicAllowed) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, name
                    )
                }
            }
            manager.enqueue(request)
            WebUtils.toast(context, context.getString(R.string.main_download_started))
            true
        }.getOrDefault(false)
    }
}
