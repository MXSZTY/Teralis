package com.mxszty.teralis.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.mxszty.teralis.R
import java.io.File

/**
 * 处理网页 &lt;input type="file"&gt;：系统文件选择器、多选、图库，以及拍照补充入口。
 */
class FileChooserHelper(registrar: ComponentActivity) {

    private val activity: Activity = registrar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val launcher = registrar.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> deliver(result) }

    fun open(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        cancel()
        filePathCallback = callback
        cameraUri = null

        val baseIntent = params.createIntent().apply {
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val captureIntent = buildCameraIntent(params)

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, baseIntent)
            putExtra(Intent.EXTRA_TITLE, activity.getString(R.string.web_upload_chooser))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (captureIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
            }
        }

        runCatching { launcher.launch(chooser) }.onFailure {
            callback.onReceiveValue(null)
            filePathCallback = null
        }
    }

    /** 仅当网页接受图片时提供拍照入口 */
    private fun buildCameraIntent(params: WebChromeClient.FileChooserParams): Intent? {
        val acceptsImage = params.acceptTypes?.any {
            it.isBlank() || it.lowercase().startsWith("image/") || it == "*/*"
        } ?: true
        if (!acceptsImage) return null
        if (activity.packageManager.hasSystemFeature("android.hardware.camera.any").not()
            && activity.packageManager.hasSystemFeature("android.hardware.camera").not()
        ) return null

        return runCatching {
            val dir = File(activity.cacheDir, "uploads").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            cameraUri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }.getOrNull()
    }

    private fun deliver(result: ActivityResult) {
        val callback = filePathCallback ?: return
        val uris: Array<Uri>? = if (result.resultCode != Activity.RESULT_OK) {
            null
        } else {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null -> arrayOf(cameraUri!!)
                else -> null
            }
        }
        callback.onReceiveValue(uris)
        filePathCallback = null
        cameraUri = null
    }

    fun cancel() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        cameraUri = null
    }
}
