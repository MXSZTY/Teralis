package com.mxszty.teralis.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.color.DynamicColors
import com.mxszty.teralis.R
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.EntryKind

/**
 * 设置容器：承载分类设置页、条目管理页与配置文件管理页。
 * 每个层级（设置首页 / 二级页）都是独立的 Activity 实例，
 * 层级间切换完全使用系统原生窗口转场，无任何自定义动画。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ConfigStore.settings.dynamicColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        enableEdgeToEdge()
        disableSystemBarContrast()
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // 手势提示条沉浸：内容延伸到导航栏区域，仅为容器补底部内边距避免被提示条遮挡
        val container = findViewById<android.view.View>(R.id.settingsContainer)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottom)
            insets
        }

        val target = intent?.getStringExtra(EXTRA_TARGET)
        toolbar.setTitle(
            when (target) {
                TARGET_URLS -> R.string.manage_url_title
                TARGET_UAS -> R.string.manage_ua_title
                TARGET_LOCATIONS -> R.string.manage_location_title
                TARGET_PROFILES -> R.string.profiles_title
                else -> R.string.settings_title
            }
        )

        if (savedInstanceState == null) {
            val fragment: Fragment = when (target) {
                TARGET_URLS -> ManageEntriesFragment.newInstance(EntryKind.URL)
                TARGET_UAS -> ManageEntriesFragment.newInstance(EntryKind.UA)
                TARGET_LOCATIONS -> ManageEntriesFragment.newInstance(EntryKind.LOCATION)
                TARGET_PROFILES -> ProfilesFragment()
                else -> SettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.settingsContainer, fragment)
                .commit()
        }
    }

    /** 导航栏沉浸：禁用系统对透明导航栏自动叠加的对比度遮罩 */
    @Suppress("DEPRECATION")
    private fun disableSystemBarContrast() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val TARGET_URLS = "urls"
        const val TARGET_UAS = "uas"
        const val TARGET_LOCATIONS = "locations"
        const val TARGET_PROFILES = "profiles"
    }
}
