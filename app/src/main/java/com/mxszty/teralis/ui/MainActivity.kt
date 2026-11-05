package com.mxszty.teralis.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.HandlerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.mxszty.teralis.R
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.EntryKind
import com.mxszty.teralis.databinding.ActivityMainBinding
import com.mxszty.teralis.util.WebUtils
import com.mxszty.teralis.web.DownloadHelper
import com.mxszty.teralis.web.FileChooserHelper
import com.mxszty.teralis.web.TeralisChromeClient
import com.mxszty.teralis.web.TeralisWebViewClient
import com.mxszty.teralis.web.WebConfigurator

class MainActivity :
    androidx.appcompat.app.AppCompatActivity(),
    TeralisWebViewClient.Host,
    TeralisChromeClient.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var fileChooser: FileChooserHelper

    private var lastRefreshTap = 0L
    private var lastSignature: String? = null
    private var restoredState = false
    /** 冷启动/跳转时已通过 intent 加载了网页，避免 onResume 再加载配置首页覆盖 */
    private var loadedFromIntent = false

    // ---- 网页权限 ----
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingDownload: DownloadRequest? = null

    private data class DownloadRequest(
        val url: String,
        val disposition: String?,
        val mimeType: String?
    )

    // ---- 全屏视频 ----
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resolvePendingGeo(granted)
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingPermission
            if (request != null && result.values.all { it }) {
                request.grant(request.resources)
            } else {
                request?.deny()
            }
            pendingPermission = null
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val download = pendingDownload
            pendingDownload = null
            if (download != null) startDownload(download)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        if (ConfigStore.settings.dynamicColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        enableEdgeToEdge()
        disableSystemBarContrast()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fileChooser = FileChooserHelper(this)

        setupWebView(savedInstanceState)
        setupAddressBar()
        setupBottomBar()
        setupContentBottomInset()
        setupFab()
        setupFindBar()
        setupEmptyAndError()
        setupBackHandling()

        // 作为默认浏览器被调起时，直接打开外部传入的网页
        if (savedInstanceState == null) handleViewIntent(intent)

        ConfigStore.addListener(configChangeListener)
    }

    /** 处理其他应用通过 ACTION_VIEW 跳转过来的 http(s) 链接；非网页 intent 返回 false */
    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        val scheme = data.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        binding.errorView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        webView.loadUrl(data.toString())
        loadedFromIntent = true
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** 导航栏沉浸：禁用系统对透明导航栏自动叠加的对比度遮罩（三键导航下尤为明显） */
    @Suppress("DEPRECATION")
    private fun disableSystemBarContrast() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private val configChangeListener: () -> Unit = {
        binding.root.post { updateContentState() }
    }

    // ---------- WebView ----------

    private fun setupWebView(savedInstanceState: Bundle?) {
        webView = binding.webView
        binding.progressBar.isIndeterminate = false
        WebConfigurator.apply(webView)
        webView.webViewClient = TeralisWebViewClient(this)
        webView.webChromeClient = TeralisChromeClient(this, this)
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            val text = if (numberOfMatches <= 0) "0/0" else "${activeMatchOrdinal + 1}/$numberOfMatches"
            binding.findBar.findCount.text = text
        }

        val webState = savedInstanceState?.getBundle(KEY_WEB_STATE)
        if (webState != null) {
            webView.restoreState(webState)
            restoredState = true
            // 状态恢复不一定触发加载/进度/标题回调，延时兜底补齐地址栏网址与标题
            binding.root.postDelayed({
                syncUrlToAddressBar()
                syncTitleFromWebView()
            }, 300)
            binding.root.postDelayed({ syncTitleFromWebView() }, 1200)
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, contentDisposition, mimeType)
        }
    }

    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val needsStoragePermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsStoragePermission) {
            pendingDownload = DownloadRequest(url, contentDisposition, mimeType)
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(DownloadRequest(url, contentDisposition, mimeType))
        }
    }

    private fun startDownload(request: DownloadRequest) {
        val ok = DownloadHelper.enqueue(
            this, request.url, request.disposition, request.mimeType
        )
        if (!ok) snackbar(getString(R.string.main_download_failed))
    }

    // ---------- 地址栏 ----------

    /** 用户未编辑时，用 WebView 当前网址补齐地址栏（用于状态恢复等无加载事件的场景）。 */
    private fun syncUrlToAddressBar() {
        if (binding.etUrl.hasFocus()) return
        if (!binding.etUrl.text.isNullOrBlank()) return
        // 存档恢复时 webView.url 可能长时间为 null，回退到持久化的当前网址
        val current = webView.url?.takeIf { it != "about:blank" }
            ?: WebUtils.normalizeUrl(ConfigStore.activeUrl()?.url.orEmpty())
        if (!current.isNullOrBlank()) binding.etUrl.setText(current)
    }

    private fun setupAddressBar() {
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.etUrl.post { binding.etUrl.selectAll() }
            } else {
                // 失焦后恢复为当前实际网址，避免用户未提交的输入残留
                val current = webView.url
                if (current != null && binding.etUrl.text?.toString() != current) {
                    binding.etUrl.setText(current)
                }
            }
        }
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitAddressBar()
                true
            } else {
                false
            }
        }
    }

    private fun submitAddressBar() {
        // 输入为网址时直接访问，否则按当前搜索引擎进行搜索
        val target = WebUtils.resolveAddressInput(
            binding.etUrl.text?.toString().orEmpty(),
            ConfigStore.settings.effectiveSearchEngine
        ) ?: return
        binding.errorView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        webView.loadUrl(target)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
        binding.etUrl.clearFocus()
        // clearFocus 后没有可聚焦目标时焦点会还给地址栏，交给 WebView 接管
        webView.requestFocus()
    }

    /** 底栏悬浮贴于 CoordinatorLayout 底部，内容列按底栏实际高度（80dp 内容 + 导航栏 inset）避让。
     *  直接消费 WindowInsets 计算，避免依赖底栏布局回调时序（否则首帧引导会偏下，直到键盘等触发重排才纠正）。 */
    private var navBarBottomInset = 0

    private fun syncContentBottomInset() {
        val bottomBarHeight = (80f * resources.displayMetrics.density).toInt() + navBarBottomInset
        val hidden = binding.bottomBar.isGone
        binding.contentColumn.updatePadding(bottom = if (hidden) 0 else bottomBarHeight)
    }

    private fun setupContentBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            navBarBottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            syncContentBottomInset()
            insets
        }
    }

    private fun setupBottomBar() {
        binding.btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else onBackPressedDispatcher.onBackPressed()
        }
        binding.btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        binding.btnSwitch.setOnClickListener {
            showQuickSwitch()
        }
        binding.btnMore.setOnClickListener { anchor ->
            // 菜单样式由主题的 popupMenuStyle 全局提供（圆角描边背景）
            val popup = androidx.appcompat.widget.PopupMenu(this, anchor, Gravity.NO_GRAVITY)
            popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
            popup.setOnMenuItemClickListener { item -> onBarMenuItem(item.itemId) }
            popup.show()
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun onBarMenuItem(itemId: Int): Boolean = when (itemId) {
        R.id.action_find -> {
            showFindBar(); true
        }
        R.id.action_page_zoom -> {
            showPageZoomDialog()
            true
        }
        R.id.action_share -> {
            webView.url?.let { WebUtils.shareText(this, it, getString(R.string.menu_share)) }
            true
        }
        R.id.action_copy -> {
            val url = webView.url ?: return true
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Teralis URL", url))
            snackbar(getString(R.string.main_url_copied))
            true
        }
        R.id.action_external -> {
            openInExternalBrowser(webView.url)
            true
        }
        R.id.action_clear_data -> {
            WebConfigurator.clearBrowsingData(webView)
            loadActiveUrl()
            snackbar(getString(R.string.main_cleared))
            true
        }
        R.id.action_settings -> {
            openSettings(null); true
        }
        else -> false
    }

    private fun setupFab() {
        val handler = HandlerCompat.createAsync(mainLooper)
        binding.fabRefresh.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastRefreshTap < DOUBLE_TAP_INTERVAL) {
                lastRefreshTap = 0L
                if (loadActiveUrl()) {
                    snackbar(getString(R.string.main_reloaded_original))
                } else {
                    snackbar(getString(R.string.main_no_url))
                }
            } else {
                lastRefreshTap = now
                if (webView.url != null) webView.reload() else loadActiveUrl()
                handler.postDelayed({ if (SystemClock.uptimeMillis() - lastRefreshTap >= DOUBLE_TAP_INTERVAL) {
                    lastRefreshTap = 0L
                } }, DOUBLE_TAP_INTERVAL + 20L)
            }
        }
    }

    private fun setupFindBar() {
        val input = binding.findBar.findInput
        binding.findBar.btnFindClose.setOnClickListener { hideFindBar() }
        binding.findBar.btnFindPrev.setOnClickListener { webView.findNext(false) }
        binding.findBar.btnFindNext.setOnClickListener { webView.findNext(true) }

        val runnable = Runnable {
            val query = input.text?.toString().orEmpty()
            if (query.isBlank()) {
                webView.clearMatches()
                binding.findBar.findCount.text = ""
            } else {
                webView.findAllAsync(query)
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                input.removeCallbacks(runnable)
                input.postDelayed(runnable, 200)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            webView.findNext(true); true
        }
    }

    private fun setupEmptyAndError() {
        binding.btnEmptyAddUrl.setOnClickListener { openSettings("urls") }
        binding.btnEmptySettings.setOnClickListener { openSettings(null) }
        binding.btnRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            webView.reload()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.etUrl.hasFocus()) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
                binding.etUrl.clearFocus()
                return@addCallback
            }
            if (binding.findBar.root.isVisible) {
                hideFindBar()
                return@addCallback
            }
            if (customView != null) {
                exitFullscreen(triggeredByWeb = false)
                return@addCallback
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return@addCallback
            }
            if (ConfigStore.settings.exitConfirm) {
                showExitConfirm()
            } else {
                finishApp()
            }
        }
    }

    private fun showExitConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_exit_title)
            .setMessage(R.string.main_exit_message)
            .setPositiveButton(R.string.main_exit_positive) { _, _ -> finishApp() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun finishApp() {
        if (ConfigStore.settings.clearDataOnExit) {
            WebConfigurator.clearBrowsingData(webView)
        }
        finish()
    }

    // ---------- 快捷切换 ----------

    private fun showQuickSwitch() {
        val sheet = QuickSwitchSheet(this)
        sheet.onUrlPicked = {
            ConfigStore.setActiveEntry(EntryKind.URL, it)
            if (!loadActiveUrl()) snackbar(getString(R.string.main_no_url))
            else snackbar(getString(R.string.switch_applied_url))
        }
        sheet.onUaPicked = {
            ConfigStore.setActiveEntry(EntryKind.UA, it)
            WebConfigurator.applyUserAgent(webView)
            webView.reload()
            snackbar(getString(R.string.switch_applied_ua))
        }
        sheet.onLocationPicked = {
            ConfigStore.setActiveEntry(EntryKind.LOCATION, it)
            if (ConfigStore.activeProfile().virtualLocationEnabled) webView.reload()
            snackbar(getString(R.string.switch_applied_location))
        }
        sheet.onVirtualToggled = { enabled ->
            ConfigStore.setVirtualLocationEnabled(enabled)
            webView.reload()
        }
        sheet.onProfilePicked = { id ->
            ConfigStore.activateProfile(id)
            applyAllSettings()
            loadActiveUrl()
            updateContentState()
            snackbar(getString(R.string.switch_profile_activated))
        }
        sheet.onManageRequested = { kind ->
            sheet.dismiss()
            openSettings(
                when (kind) {
                    EntryKind.URL -> "urls"
                    EntryKind.UA -> "uas"
                    EntryKind.LOCATION -> "locations"
                }
            )
        }
        sheet.setOnDismissListener {
            // 面板内的切换已即时生效并加载，同步签名，避免返回前台时重复刷新
            lastSignature = currentSignature()
        }
        sheet.show()
    }

    private fun openSettings(target: String?) {
        val intent = Intent(this, SettingsActivity::class.java)
        if (target != null) intent.putExtra(SettingsActivity.EXTRA_TARGET, target)
        startActivity(intent)
    }

    // ---------- 页面状态 ----------

    private fun loadActiveUrl(): Boolean {
        val url = WebUtils.normalizeUrl(ConfigStore.activeUrl()?.url.orEmpty()) ?: return false
        binding.errorView.visibility = View.GONE
        webView.loadUrl(url)
        return true
    }

    private fun applyAllSettings() {
        WebConfigurator.apply(webView)
    }

    private fun updateContentState() {
        // 没有配置网址时，只要用户已通过地址栏手动打开了网页，也不再显示添加入口引导
        val currentUrl = webView.url
        val hasContent = ConfigStore.activeUrl() != null ||
            (!currentUrl.isNullOrBlank() && currentUrl != "about:blank")
        binding.emptyView.visibility = if (hasContent) View.GONE else View.VISIBLE
        syncUrlToAddressBar()
        updateNavigationState()
    }

    private fun updateNavigationState() {
        val canForward = webView.canGoForward()
        binding.btnForward.isEnabled = canForward
        binding.btnForward.alpha = if (canForward) 1f else 0.38f
    }

    override fun onMainFrameStarted(url: String) {
        binding.progressBar.setProgressCompat(0, false)
        binding.progressBar.show()
        binding.errorView.visibility = View.GONE
        // 用户正在输入时不要打断
        if (!binding.etUrl.hasFocus()) binding.etUrl.setText(url)
        binding.tvPageTitle.visibility = View.GONE
        updateNavigationState()
    }

    override fun onMainFrameFinished(url: String) {
        binding.progressBar.hide()
        if (!binding.etUrl.hasFocus()) binding.etUrl.setText(url)
        binding.errorView.visibility = View.GONE
        updateNavigationState()
    }

    override fun onMainFrameError(url: String, errorCode: Int, description: String?) {
        // about:blank 或被拦截的非 http 跳转不算失败
        if (url.isBlank() || url.startsWith("about:")) return
        binding.errorView.visibility = View.VISIBLE
        binding.progressBar.hide()
        updateNavigationState()
    }

    override fun shouldOpenExternally(url: String): Boolean {
        // http(s) 由 WebView 内部加载，其余 scheme 交给外部应用
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return false
        val handled = WebUtils.launchExternal(this, url)
        if (!handled) snackbar(getString(R.string.main_no_external_browser))
        return true
    }

    // ---------- ChromeClient.Host ----------

    override fun onLoadProgress(progress: Int) {
        binding.progressBar.setProgressCompat(progress, true)
        syncUrlToAddressBar()
        syncTitleFromWebView()
        if (progress >= 100) binding.progressBar.postDelayed({
            binding.progressBar.hide()
        }, 200)
    }

    /** 从 WebView 实时同步标题：存档恢复/缓存加载可能收不到 onReceivedTitle 回调 */
    private fun syncTitleFromWebView() {
        val title = webView.title
        if (title.isNullOrBlank() || title == "about:blank") return
        if (binding.tvPageTitle.visibility != View.VISIBLE ||
            binding.tvPageTitle.text?.toString() != title
        ) {
            binding.tvPageTitle.text = title
            binding.tvPageTitle.visibility = View.VISIBLE
        }
    }

    override fun onPageTitleReceived(title: String?) {
        syncUrlToAddressBar()
        val valid = !title.isNullOrBlank() && title != "about:blank"
        if (valid) {
            binding.tvPageTitle.text = title
            binding.tvPageTitle.visibility = View.VISIBLE
        } else {
            binding.tvPageTitle.visibility = View.GONE
        }
    }

    override fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ) {
        fileChooser.open(callback, params)
    }

    override fun onGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (ConfigStore.activeProfile().virtualLocationEnabled) {
            // 虚拟位置：坐标由 JS 提供，直接放行，避免网页卡在授权环节
            callback.invoke(origin, true, false)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            proceedRealGeolocation(origin, callback)
            return
        }
        pendingGeoOrigin = origin
        pendingGeoCallback = callback
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.web_location_rationale)
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton(R.string.common_cancel) { _, _ -> resolvePendingGeo(false) }
                .setOnCancelListener { resolvePendingGeo(false) }
                .show()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** 系统定位权限已具备时，按设置决定直接放行或先询问用户 */
    private fun proceedRealGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        if (ConfigStore.settings.autoGrantGeolocation) {
            callback.invoke(origin, true, false)
            return
        }
        val host = runCatching { origin.toUri().host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: origin
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.web_geo_site_prompt, host))
            .setPositiveButton(R.string.web_geo_allow_once) { _, _ ->
                callback.invoke(origin, true, false)
            }
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                callback.invoke(origin, false, false)
            }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    private fun resolvePendingGeo(granted: Boolean) {
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin.orEmpty()
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (cb == null) return
        if (granted) proceedRealGeolocation(origin, cb) else cb.invoke(origin, false, false)
    }

    override fun onWebPermissionRequest(request: PermissionRequest) {
        val wanted = request.resources
        val required = mutableListOf<String>()
        wanted.forEach {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> required.add(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    required.add(Manifest.permission.RECORD_AUDIO)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.distinct()

        if (missing.isEmpty()) {
            request.grant(wanted)
            return
        }
        pendingPermission = request
        val needRationale = missing.any { shouldShowRequestPermissionRationale(it) }
        if (needRationale) {
            val messageRes = if (Manifest.permission.CAMERA in missing) {
                if (Manifest.permission.RECORD_AUDIO in missing) R.string.web_permission_camera_mic
                else R.string.web_permission_camera
            } else {
                R.string.web_permission_mic
            }
            MaterialAlertDialogBuilder(this)
                .setMessage(messageRes)
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    mediaPermissionLauncher.launch(missing.toTypedArray())
                }
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    request.deny()
                    pendingPermission = null
                }
                .setOnCancelListener {
                    request.deny()
                    pendingPermission = null
                }
                .show()
        } else {
            mediaPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- 全屏视频 ----------

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        customCallback?.onCustomViewHidden()
        customView = view
        customCallback = callback
        binding.fullscreenContainer.addView(
            view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.appBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.fabRefresh.visibility = View.GONE
        syncContentBottomInset()
        WindowInsetsControllerCompat(window, binding.root).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideFullscreen() = exitFullscreen(triggeredByWeb = true)

    private fun exitFullscreen(triggeredByWeb: Boolean) {
        val callback = customCallback
        (customView?.parent as? ViewGroup)?.removeView(customView)
        customView = null
        customCallback = null
        binding.fullscreenContainer.visibility = View.GONE
        binding.appBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        binding.fabRefresh.visibility = View.VISIBLE
        syncContentBottomInset()
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        if (!triggeredByWeb) callback?.onCustomViewHidden()
    }

    // ---------- 页面整体缩放 ----------

    private fun showPageZoomDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_page_zoom, null)
        val slider = view.findViewById<Slider>(R.id.sliderZoom)
        val percentView = view.findViewById<android.widget.TextView>(R.id.tvZoomPercent)
        val original = ConfigStore.settings.pageZoomPercent.coerceIn(
            WebConfigurator.MIN_PAGE_ZOOM,
            WebConfigurator.MAX_PAGE_ZOOM
        )
        slider.value = original.toFloat()
        slider.setLabelFormatter { "${it.toInt()}%" }
        percentView.text = getString(R.string.page_zoom_value, original)

        fun applyZoom(percent: Int) {
            percentView.text = getString(R.string.page_zoom_value, percent)
            if (ConfigStore.settings.pageZoomPercent != percent) {
                ConfigStore.updateSettings { it.copy(pageZoomPercent = percent) }
                // 对当前页面实时生效；setInitialScale 保证后续打开的页面同样缩放
                WebConfigurator.applyInitialPageScale(webView)
                WebConfigurator.applyPageZoom(webView)
            }
        }
        slider.addOnChangeListener { _, value, _ -> applyZoom(value.toInt()) }

        var confirmed = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.page_zoom_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.page_zoom_reset, null)
            .setPositiveButton(R.string.common_confirm) { _, _ -> confirmed = true }
            .create()
        // “重置”只回到 100%，不关闭对话框
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                slider.value = 100f
            }
        }
        // 取消时恢复打开对话框前的缩放比例
        dialog.setOnDismissListener {
            if (!confirmed && ConfigStore.settings.pageZoomPercent != original) {
                ConfigStore.updateSettings { it.copy(pageZoomPercent = original) }
                WebConfigurator.applyInitialPageScale(webView)
                WebConfigurator.applyPageZoom(webView)
            }
        }
        dialog.show()
    }

    // ---------- 查找栏 ----------

    private fun showFindBar() {
        binding.findBar.root.visibility = View.VISIBLE
        binding.findBar.findInput.requestFocus()
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideFindBar() {
        webView.clearMatches()
        binding.findBar.findCount.text = ""
        binding.findBar.findInput.text?.clear()
        binding.findBar.root.visibility = View.GONE
        WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.ime())
    }

    // ---------- 外部浏览器 / 其他 ----------

    private fun openInExternalBrowser(url: String?) {
        if (url.isNullOrBlank()) return
        // 直接交由系统解析默认浏览器；Android 11+ 不再依赖 resolveActivity 可见性判断
        val handled = WebUtils.openInBrowser(this, url)
        if (!handled) snackbar(getString(R.string.main_no_external_browser))
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.bottomBar)
            .show()
    }

    // ---------- 生命周期 ----------

    override fun onResume() {
        super.onResume()
        applyAllSettings()
        updateContentState()

        val newSignature = currentSignature()
        val old = lastSignature
        if (old == null) {
            if (!restoredState && !loadedFromIntent && webView.url == null) loadActiveUrl()
            restoredState = false
            loadedFromIntent = false
        } else if (old != newSignature) {
            val oldProfile = old.substringBefore('|')
            val oldUrl = old.split('|').getOrNull(1)
            if (oldProfile != newSignature.substringBefore('|') || oldUrl != newSignature.split('|')[1]) {
                loadActiveUrl()
            } else if (webView.url != null) {
                webView.reload()
            }
        }
        lastSignature = newSignature

        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webBundle = Bundle()
        webView.saveState(webBundle)
        outState.putBundle(KEY_WEB_STATE, webBundle)
    }

    override fun onDestroy() {
        ConfigStore.removeListener(configChangeListener)
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun currentSignature(): String {
        val p = ConfigStore.activeProfile()
        return listOf(
            p.id, p.activeUrlId.orEmpty(), p.activeUaId.orEmpty(),
            p.activeLocationId.orEmpty(), p.virtualLocationEnabled.toString()
        ).joinToString("|")
    }

    companion object {
        private const val DOUBLE_TAP_INTERVAL = 300L
        private const val KEY_WEB_STATE = "teralis_web_state"
    }
}
