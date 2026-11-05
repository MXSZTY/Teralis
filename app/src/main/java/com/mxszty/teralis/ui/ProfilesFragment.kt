package com.mxszty.teralis.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mxszty.teralis.R
import com.mxszty.teralis.data.ConfigProfile
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.databinding.FragmentProfilesBinding
import java.io.File

/** 多套配置文件的管理：新建、重命名、复制、删除、导入、导出与分享。 */
class ProfilesFragment : Fragment() {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!
    private val adapter = ProfileAdapter()
    private var pendingSaveProfileId: String? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doSave(uri, pendingSaveProfileId)
            pendingSaveProfileId = null
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener { showCreateDialog() }
        applyBottomInset()

        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_profiles, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    if (menuItem.itemId == R.id.action_import) {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        true
                    } else false
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(ConfigStore.profiles, ConfigStore.activeProfileId)
        binding.emptyView.visibility =
            if (ConfigStore.profiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun applyBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.profilesRoot) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.recycler.updatePadding(bottom = (96 * resources.displayMetrics.density).toInt() + bottom)
            binding.fabAdd.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * resources.displayMetrics.density).toInt() + bottom
            }
            insets
        }
    }

    // ---------- 操作 ----------

    private fun showCreateDialog() {
        promptName(R.string.common_add, getString(R.string.profile_new_name)) { name ->
            ConfigStore.addProfile(name)
            refresh()
        }
    }

    private fun showRenameDialog(profile: ConfigProfile) {
        promptName(R.string.common_rename, profile.name) { name ->
            ConfigStore.renameProfile(profile.id, name)
            refresh()
        }
    }

    private fun promptName(titleRes: Int, initial: String, onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelection(initial.length)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) onConfirm(name)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDelete(profile: ConfigProfile) {
        if (ConfigStore.profiles.size <= 1) {
            snackbar(R.string.profile_delete_last)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.common_delete)
            .setMessage(getString(R.string.profile_delete_confirm, profile.name))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                ConfigStore.deleteProfile(profile.id)
                refresh()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showOverflow(anchor: View, profile: ConfigProfile) {
        // 菜单样式由主题的 popupMenuStyle 全局提供，与主界面“更多”菜单一致
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_profile_actions, popup.menu)
        if (profile.id == ConfigStore.activeProfileId) {
            popup.menu.findItem(R.id.action_activate)?.isEnabled = false
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_activate -> {
                    ConfigStore.activateProfile(profile.id)
                    refresh()
                    snackbar(getString(R.string.profile_activated_done, profile.name))
                    true
                }
                R.id.action_rename -> { showRenameDialog(profile); true }
                R.id.action_duplicate -> {
                    ConfigStore.duplicateProfile(profile.id)
                    refresh(); true
                }
                R.id.action_share -> { shareProfile(profile); true }
                R.id.action_save_file -> {
                    pendingSaveProfileId = profile.id
                    saveLauncher.launch(suggestedFileName(profile.name))
                    true
                }
                R.id.action_delete -> { confirmDelete(profile); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---------- 导入 / 导出 / 分享 ----------

    private fun doImport(uri: Uri) {
        val json = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val imported = json?.let { ConfigStore.importProfile(it) }
        if (imported != null) {
            refresh()
            snackbar(getString(R.string.profile_import_success, imported.name))
        } else {
            snackbar(R.string.profile_import_failed)
        }
    }

    private fun doSave(uri: Uri, profileId: String?) {
        val json = profileId?.let { ConfigStore.exportProfile(it) }
        val ok = json != null && runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        snackbar(if (ok) R.string.common_save else R.string.profile_export_failed)
    }

    private fun shareProfile(profile: ConfigProfile) {
        val json = ConfigStore.exportProfile(profile.id) ?: run {
            snackbar(R.string.profile_export_failed)
            return
        }
        val dir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, suggestedFileName(profile.name))
        file.writeText(json, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, profile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.common_share)))
        }.onFailure { snackbar(R.string.profile_export_failed) }
    }

    private fun suggestedFileName(profileName: String): String {
        val safe = profileName.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "config" }
        return "teralis-${safe}.json"
    }

    private fun snackbar(messageRes: Int) {
        _binding?.let { Snackbar.make(it.root, messageRes, Snackbar.LENGTH_SHORT).show() }
    }

    private fun snackbar(message: String) {
        _binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 适配器 ----------

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {

        private val items = mutableListOf<ConfigProfile>()
        private var activeProfileId: String? = null

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<ConfigProfile>, activeId: String?) {
            items.clear()
            items.addAll(newItems)
            activeProfileId = activeId
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView as MaterialCardView
            val name: TextView = itemView.findViewById(R.id.tvProfileName)
            val summary: TextView = itemView.findViewById(R.id.tvProfileSummary)
            val activeMark: ImageView = itemView.findViewById(R.id.imgActive)
            val overflow: ImageView = itemView.findViewById(R.id.btnOverflow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_profile, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val profile = items[position]
            val isActive = profile.id == activeProfileId
            holder.name.text = profile.name
            holder.summary.text = getString(
                R.string.profiles_summary,
                profile.urls.size,
                profile.uas.size,
                profile.locations.size
            )
            holder.activeMark.visibility = if (isActive) View.VISIBLE else View.GONE
            holder.card.strokeWidth =
                (2 * resources.displayMetrics.density).toInt().takeIf { isActive } ?: 1
            holder.card.strokeColor = com.google.android.material.color.MaterialColors.getColor(
                holder.card,
                if (isActive) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant
            )
            holder.card.setOnClickListener {
                if (!isActive) {
                    ConfigStore.activateProfile(profile.id)
                    refresh()
                    snackbar(getString(R.string.profile_activated_done, profile.name))
                }
            }
            holder.overflow.setOnClickListener { showOverflow(it, profile) }
        }

        override fun getItemCount(): Int = items.size
    }
}
