package com.mxszty.teralis

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.ThemeMode

class TeralisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        applyNightMode(ConfigStore.settings.themeMode)
    }

    companion object {
        fun applyNightMode(mode: ThemeMode) {
            val delegateMode = when (mode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(delegateMode)
        }
    }
}
