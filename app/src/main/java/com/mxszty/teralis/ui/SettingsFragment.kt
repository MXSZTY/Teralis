package com.mxszty.teralis.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.mxszty.teralis.R
import com.mxszty.teralis.TeralisApp
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.EntryKind
import com.mxszty.teralis.data.SearchEngine
import com.mxszty.teralis.data.ThemeMode
import com.mxszty.teralis.databinding.FragmentSettingsBinding
import com.mxszty.teralis.util.UpdateChecker
import com.mxszty.teralis.util.WebUtils

/** 设置首页：按类别分组的全部全局设置。 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var updating = true

    private var pendingExportProfileId: String? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyBottomInset()

        // 配置文件
        binding.rowActiveProfile.setOnClickListener { showProfilePicker() }
        binding.rowManageProfiles.setOnClickListener { open(ProfilesFragment()) }
        binding.rowImportConfig.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.rowExportConfig.setOnClickListener { exportCurrentProfile() }

        // 基础配置
        binding.rowManageUrls.setOnClickListener { open(ManageEntriesFragment.newInstance(EntryKind.URL)) }
        binding.rowManageUas.setOnClickListener { open(ManageEntriesFragment.newInstance(EntryKind.UA)) }
        binding.rowManageLocations.setOnClickListener { open(ManageEntriesFragment.newInstance(EntryKind.LOCATION)) }

        // 外观
        binding.rowTheme.setOnClickListener { showThemePicker() }
        binding.switchDynamicColor.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            ConfigStore.updateSettings { it.copy(dynamicColor = checked) }
            requireActivity().recreate()
        }

        // 浏览
        binding.rowSearchEngine.setOnClickListener { showSearchEnginePicker() }

        bindSwitch(binding.switchJavaScript)
        bindSwitch(binding.switchDomStorage)
        bindSwitch(binding.switchZoom)
        bindSwitch(binding.switchFileAccess)
        bindSwitch(binding.switchAutoGeo)
        bindSwitch(binding.switchDeepInject)
        bindSwitch(binding.switchExitConfirm)
        bindSwitch(binding.switchClearOnExit)
        bindSwitch(binding.switchAutoUpdate)

        // 获取更新
        binding.rowCheckUpdate.setOnClickListener { checkUpdateManually() }

        // 关于
        binding.rowGithub.setOnClickListener {
            val opened = WebUtils.openInBrowser(
                requireContext(),
                getString(R.string.settings_github_url)
            )
            if (!opened) snackbar(R.string.main_no_external_browser)
        }
        binding.rowDisclaimer.setOnClickListener {
            showInfoDialog(R.string.settings_disclaimer, R.string.settings_disclaimer_text)
        }
        binding.rowOpenSource.setOnClickListener {
            showInfoDialog(R.string.settings_open_source, R.string.settings_open_source_text)
        }

        refresh()
    }

    private fun bindSwitch(switchView: MaterialSwitch) {
        switchView.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            ConfigStore.updateSettings { current ->
                when (switchView.id) {
                    R.id.switchJavaScript -> current.copy(javaScriptEnabled = checked)
                    R.id.switchDomStorage -> current.copy(domStorageEnabled = checked)
                    R.id.switchZoom -> current.copy(pinchZoomEnabled = checked)
                    R.id.switchFileAccess -> current.copy(fileAccessEnabled = checked)
                    R.id.switchAutoGeo -> current.copy(autoGrantGeolocation = checked)
                    R.id.switchDeepInject -> current.copy(deepLocationInject = checked)
                    R.id.switchExitConfirm -> current.copy(exitConfirm = checked)
                    R.id.switchClearOnExit -> current.copy(clearDataOnExit = checked)
                    R.id.switchAutoUpdate -> current.copy(autoCheckUpdate = checked)
                    else -> current
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        updating = true
        val s = ConfigStore.settings
        val p = ConfigStore.activeProfile()
        binding.tvActiveProfile.text = p.name
        binding.tvThemeValue.text = getString(
            when (s.themeMode) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
            }
        )
        binding.tvSearchEngineValue.text = getString(searchEngineLabel(s.effectiveSearchEngine))
        binding.switchDynamicColor.isChecked = s.dynamicColor
        binding.switchJavaScript.isChecked = s.javaScriptEnabled
        binding.switchDomStorage.isChecked = s.domStorageEnabled
        binding.switchZoom.isChecked = s.pinchZoomEnabled
        binding.switchFileAccess.isChecked = s.fileAccessEnabled
        binding.switchAutoGeo.isChecked = s.autoGrantGeolocation
        binding.switchDeepInject.isChecked = s.deepLocationInject
        binding.switchExitConfirm.isChecked = s.exitConfirm
        binding.switchClearOnExit.isChecked = s.clearDataOnExit
        binding.switchAutoUpdate.isChecked = s.effectiveAutoCheckUpdate

        val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.switchDynamicColor.isEnabled = dynamicSupported
        binding.rowDynamicColor.isEnabled = dynamicSupported
        binding.switchDynamicColor.alpha = if (dynamicSupported) 1f else 0.45f

        binding.tvVersionValue.text = runCatching {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            info.versionName ?: "1.0"
        }.getOrDefault("1.0")
        updating = false
    }

    private var checkingUpdate = false

    /** 手动检查更新：无论结果如何都给出反馈（自动检查则静默处理无更新/失败） */
    private fun checkUpdateManually() {
        if (checkingUpdate) return
        checkingUpdate = true
        snackbar(R.string.update_checking)
        Thread {
            val result = UpdateChecker.check(UpdateChecker.currentVersion(requireContext()))
            activity?.runOnUiThread {
                checkingUpdate = false
                if (!isAdded) return@runOnUiThread
                when (result) {
                    is UpdateChecker.CheckResult.UpdateAvailable ->
                        UpdateChecker.showUpdateDialog(requireContext(), result)
                    UpdateChecker.CheckResult.UpToDate -> snackbar(R.string.update_none)
                    UpdateChecker.CheckResult.Failed -> snackbar(R.string.update_failed)
                }
            }
        }.start()
    }

    private fun showThemePicker() {
        val modes = ThemeMode.entries.toTypedArray()
        val labels = modes.map {
            getString(
                when (it) {
                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                    ThemeMode.LIGHT -> R.string.settings_theme_light
                    ThemeMode.DARK -> R.string.settings_theme_dark
                }
            )
        }.toTypedArray()
        val current = modes.indexOf(ConfigStore.settings.themeMode)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = modes[which]
                if (mode != ConfigStore.settings.themeMode) {
                    ConfigStore.updateSettings { it.copy(themeMode = mode) }
                    TeralisApp.applyNightMode(mode)
                    refresh()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showSearchEnginePicker() {
        val engines = SearchEngine.entries.toTypedArray()
        val labels = engines.map { getString(searchEngineLabel(it)) }.toTypedArray()
        val current = engines.indexOf(ConfigStore.settings.effectiveSearchEngine)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_search_engine)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                if (engines[which] != ConfigStore.settings.effectiveSearchEngine) {
                    ConfigStore.updateSettings { it.copy(searchEngine = engines[which]) }
                }
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun searchEngineLabel(engine: SearchEngine): Int = when (engine) {
        SearchEngine.BAIDU -> R.string.settings_engine_baidu
        SearchEngine.BING -> R.string.settings_engine_bing
        SearchEngine.SOGOU -> R.string.settings_engine_sogou
    }

    private fun showProfilePicker() {
        val profiles = ConfigStore.profiles
        val names = profiles.map { it.name }.toTypedArray()
        val current = profiles.indexOfFirst { it.id == ConfigStore.activeProfileId }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_active_profile)
            .setSingleChoiceItems(names, current) { dialog, which ->
                ConfigStore.activateProfile(profiles[which].id)
                refresh()
                dialog.dismiss()
            }
            .setPositiveButton(R.string.settings_manage_profiles) { _, _ -> open(ProfilesFragment()) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showInfoDialog(titleRes: Int, messageRes: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    // ---------- 导入 / 导出 ----------

    private fun exportCurrentProfile() {
        val p = ConfigStore.activeProfile()
        pendingExportProfileId = p.id
        exportLauncher.launch(suggestedFileName(p.name))
    }

    private fun doExport(uri: Uri) {
        val id = pendingExportProfileId
        val json = id?.let { ConfigStore.exportProfile(it) }
        pendingExportProfileId = null
        val ok = json != null && runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        snackbar(
            if (ok) R.string.profile_export_saved else R.string.profile_export_failed
        )
    }

    private fun doImport(uri: Uri) {
        val json = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val imported = json?.let { ConfigStore.importProfile(it) }
        if (imported != null) {
            snackbar(getString(R.string.profile_import_success, imported.name))
            refresh()
        } else {
            snackbar(R.string.profile_import_failed)
        }
    }

    private fun suggestedFileName(profileName: String): String {
        val safe = profileName.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "config" }
        return "teralis-${safe}.json"
    }

    /** 二级设置页以独立 Activity 打开，进入与返回均为系统原生窗口转场 */
    private fun open(fragment: Fragment) {
        val target = when (fragment) {
            is ProfilesFragment -> SettingsActivity.TARGET_PROFILES
            is ManageEntriesFragment -> when (fragment.arguments?.getString(ManageEntriesFragment.ARG_KIND)) {
                EntryKind.URL.name -> SettingsActivity.TARGET_URLS
                EntryKind.UA.name -> SettingsActivity.TARGET_UAS
                else -> SettingsActivity.TARGET_LOCATIONS
            }
            else -> return
        }
        startActivity(
            Intent(requireContext(), SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_TARGET, target)
        )
    }

    private fun applyBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsScroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = (28 * resources.displayMetrics.density).toInt() + bottom)
            insets
        }
    }

    private fun snackbar(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
