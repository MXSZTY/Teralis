package com.mxszty.teralis.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.mxszty.teralis.R
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.EntryKind

/**
 * 主界面快捷切换面板：一键切换当前配置文件下的网址 / UA / 虚拟位置，
 * 以及真实位置 / 虚拟位置总开关。
 */
class QuickSwitchSheet(context: Context) :
    BottomSheetDialog(context, R.style.Theme_Teralis_BottomSheet) {

    var onUrlPicked: ((String?) -> Unit)? = null
    var onUaPicked: ((String?) -> Unit)? = null
    var onLocationPicked: ((String?) -> Unit)? = null
    var onVirtualToggled: ((Boolean) -> Unit)? = null
    var onProfilePicked: ((String) -> Unit)? = null
    var onManageRequested: ((EntryKind) -> Unit)? = null

    private lateinit var tvProfileName: TextView
    private lateinit var tvVirtualSubtitle: TextView
    private lateinit var switchVirtual: MaterialSwitch
    private var suppressToggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.sheet_quick_switch)

        tvProfileName = findViewById(R.id.tvProfileName)!!
        tvVirtualSubtitle = findViewById(R.id.tvVirtualSubtitle)!!
        switchVirtual = findViewById(R.id.switchVirtual)!!

        findViewById<View>(R.id.cardProfile)!!.setOnClickListener { pickProfile() }
        findViewById<MaterialButton>(R.id.btnSwitchProfile)!!.setOnClickListener { pickProfile() }

        switchVirtual.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            onVirtualToggled?.invoke(checked)
            tvVirtualSubtitle.setText(
                if (checked) R.string.switch_virtual_location_on
                else R.string.switch_virtual_location_off
            )
        }

        bindRow(
            R.id.rowUrl, R.drawable.ic_link,
            context.getString(R.string.switch_section_url)
        ) { showEntryPicker(EntryKind.URL) }
        bindRow(
            R.id.rowUa, R.drawable.ic_devices,
            context.getString(R.string.switch_section_ua)
        ) { showEntryPicker(EntryKind.UA) }
        bindRow(
            R.id.rowLocation, R.drawable.ic_explore,
            context.getString(R.string.switch_section_location)
        ) { showEntryPicker(EntryKind.LOCATION) }

        refresh()
    }

    private fun bindRow(rowId: Int, iconRes: Int, label: String, onClick: () -> Unit) {
        val row = findViewById<View>(rowId)!!
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        row.setOnClickListener { onClick() }
        row.findViewById<MaterialButton>(R.id.btnPick).setOnClickListener { onClick() }
    }

    private fun refresh() {
        val profile = ConfigStore.activeProfile()
        tvProfileName.text = profile.name
        suppressToggle = true
        switchVirtual.isChecked = profile.virtualLocationEnabled
        suppressToggle = false
        tvVirtualSubtitle.setText(
            if (profile.virtualLocationEnabled) R.string.switch_virtual_location_on
            else R.string.switch_virtual_location_off
        )

        rowValue(R.id.rowUrl).text = ConfigStore.activeUrl()?.name
            ?: context.getString(R.string.switch_none_url)

        val ua = ConfigStore.activeUa()
        rowValue(R.id.rowUa).text = when {
            ua == null -> context.getString(R.string.switch_ua_system)
            ua.systemDefault -> context.getString(R.string.switch_ua_system)
            else -> ua.name
        }

        rowValue(R.id.rowLocation).text = ConfigStore.activeLocation()?.name
            ?: context.getString(R.string.switch_none_location)
    }

    private fun rowValue(rowId: Int): TextView =
        findViewById<View>(rowId)!!.findViewById(R.id.rowValue)

    // ---------- 选择器 ----------

    private fun pickProfile() {
        val profiles = ConfigStore.profiles
        val names = profiles.map { it.name }.toTypedArray()
        val checked = profiles.indexOfFirst { it.id == ConfigStore.activeProfileId }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.switch_change_profile)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                onProfilePicked?.invoke(profiles[which].id)
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showEntryPicker(kind: EntryKind) {
        val profile = ConfigStore.activeProfile()
        val ids = mutableListOf<String?>()
        val names = mutableListOf<String>()
        val checked: Int
        when (kind) {
            EntryKind.URL -> {
                if (profile.urls.isEmpty()) return askManage(kind, R.string.switch_none_url)
                profile.urls.forEach { ids.add(it.id); names.add(it.name) }
                checked = profile.urls.indexOfFirst { it.id == profile.activeUrlId }
            }
            EntryKind.UA -> {
                // 首项固定为“系统默认 UA”（id 为 null），始终可选
                ids.add(null)
                names.add(context.getString(R.string.switch_ua_system))
                // 旧版本可能存在标记为系统默认的自定义条目，不再重复展示
                val customUas = profile.uas.filter { !it.systemDefault }
                customUas.forEach {
                    ids.add(it.id)
                    names.add(it.name)
                }
                checked = customUas.indexOfFirst { it.id == profile.activeUaId } + 1
            }
            EntryKind.LOCATION -> {
                if (profile.locations.isEmpty()) return askManage(kind, R.string.switch_none_location)
                profile.locations.forEach { ids.add(it.id); names.add(it.name) }
                checked = profile.locations.indexOfFirst { it.id == profile.activeLocationId }
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(
                when (kind) {
                    EntryKind.URL -> R.string.switch_pick_url
                    EntryKind.UA -> R.string.switch_pick_ua
                    EntryKind.LOCATION -> R.string.switch_pick_location
                }
            )
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                val id = ids[which]
                when (kind) {
                    EntryKind.URL -> onUrlPicked?.invoke(id)
                    EntryKind.UA -> onUaPicked?.invoke(id)
                    EntryKind.LOCATION -> onLocationPicked?.invoke(id)
                }
                refresh()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.common_manage) { _, _ -> onManageRequested?.invoke(kind) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun askManage(kind: EntryKind, messageRes: Int) {
        MaterialAlertDialogBuilder(context)
            .setMessage(messageRes)
            .setPositiveButton(R.string.common_manage) { _, _ -> onManageRequested?.invoke(kind) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
