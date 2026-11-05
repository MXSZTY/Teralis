package com.mxszty.teralis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mxszty.teralis.R
import com.mxszty.teralis.data.ConfigStore
import com.mxszty.teralis.data.EntryKind
import com.mxszty.teralis.data.LocationEntry
import com.mxszty.teralis.data.UaEntry
import com.mxszty.teralis.data.UrlEntry
import com.mxszty.teralis.databinding.FragmentManageBinding
import com.mxszty.teralis.util.WebUtils

/** 网址 / UA / 虚拟位置三类条目的通用管理页。 */
class ManageEntriesFragment : Fragment() {

    private val kind: EntryKind
        get() = EntryKind.valueOf(
            requireArguments().getString(ARG_KIND) ?: EntryKind.URL.name
        )

    private var _binding: FragmentManageBinding? = null
    private val binding get() = _binding!!
    private val adapter = EntryAdapter()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingLocationFill?.invoke()
            } else {
                snackbar(R.string.location_permission_needed)
            }
            pendingLocationFill = null
        }

    private var pendingLocationFill: (() -> Unit)? = null
    private var locationHandler: Handler? = null
    private var activeLocationListener: LocationListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        applyBottomInset()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun entries(): List<Any> {
        val p = ConfigStore.activeProfile()
        return when (kind) {
            EntryKind.URL -> p.urls
            EntryKind.UA -> p.uas
            EntryKind.LOCATION -> p.locations
        }
    }

    private fun activeId(): String? {
        val p = ConfigStore.activeProfile()
        return when (kind) {
            EntryKind.URL -> p.activeUrlId
            EntryKind.UA -> p.activeUaId
            EntryKind.LOCATION -> p.activeLocationId
        }
    }

    private fun refresh() {
        val items = entries()
        adapter.submit(items, activeId())
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.setText(
            when (kind) {
                EntryKind.URL -> R.string.manage_url_empty
                EntryKind.UA -> R.string.manage_ua_empty
                EntryKind.LOCATION -> R.string.manage_location_empty
            }
        )
    }

    private fun applyBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.manageRoot) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.recycler.updatePadding(bottom = (96 * resources.displayMetrics.density).toInt() + bottom)
            binding.fabAdd.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * resources.displayMetrics.density).toInt() + bottom
            }
            insets
        }
    }

    // ---------- 编辑对话框 ----------

    private fun showEditDialog(existingId: String?) {
        when (kind) {
            EntryKind.URL -> showUrlDialog(existingId)
            EntryKind.UA -> showUaDialog(existingId)
            EntryKind.LOCATION -> showLocationDialog(existingId)
        }
    }

    private fun showUrlDialog(existingId: String?) {
        val existing = existingId?.let { id -> ConfigStore.activeProfile().urls.firstOrNull { it.id == id } }
        val view = layoutInflater.inflate(R.layout.dialog_edit_url, null)
        val edtName = view.findViewById<EditText>(R.id.edtName)
        val edtUrl = view.findViewById<EditText>(R.id.edtUrl)
        existing?.let {
            edtName.setText(it.name)
            edtUrl.setText(it.url)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.url_add_title else R.string.url_edit_title)
            .setView(view)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val raw = edtUrl.text?.toString().orEmpty().trim()
                val normalized = WebUtils.normalizeUrl(raw)
                if (normalized == null) {
                    snackbar(R.string.url_invalid)
                    return@setPositiveButton
                }
                val name = edtName.text?.toString()?.trim().orEmpty()
                    .ifBlank { hostOf(normalized) }
                if (existing == null) {
                    ConfigStore.addUrl(UrlEntry(name = name, url = normalized))
                } else {
                    ConfigStore.updateUrl(existing.copy(name = name, url = normalized))
                }
                refresh()
            }
            .setNegativeButton(if (existing == null) R.string.common_cancel else R.string.common_delete, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                ?.let { btn ->
                    if (existing != null) {
                        btn.setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                btn, com.google.android.material.R.attr.colorError
                            )
                        )
                        btn.setOnClickListener {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.common_delete)
                                .setMessage(R.string.common_delete_confirm)
                                .setPositiveButton(R.string.common_delete) { _, _ ->
                                    ConfigStore.deleteUrl(existing.id)
                                    dialog.dismiss()
                                    refresh()
                                }
                                .setNegativeButton(R.string.common_cancel, null)
                                .show()
                        }
                    } else {
                        btn.setOnClickListener { dialog.dismiss() }
                    }
                }
        }
        dialog.show()
    }

    private fun showUaDialog(existingId: String?) {
        val existing = existingId?.let { id -> ConfigStore.activeProfile().uas.firstOrNull { it.id == id } }
        val view = layoutInflater.inflate(R.layout.dialog_edit_ua, null)
        val edtName = view.findViewById<EditText>(R.id.edtName)
        val edtUa = view.findViewById<EditText>(R.id.edtUa)
        existing?.let {
            edtName.setText(it.name)
            edtUa.setText(it.userAgent)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.ua_add_title else R.string.ua_edit_title)
            .setView(view)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val ua = edtUa.text?.toString().orEmpty().trim()
                if (ua.isBlank()) {
                    snackbar(R.string.ua_invalid)
                    return@setPositiveButton
                }
                val name = edtName.text?.toString()?.trim().orEmpty()
                    .ifBlank { "自定义 UA" }
                if (existing == null) {
                    ConfigStore.addUa(UaEntry(name = name, userAgent = ua))
                } else {
                    ConfigStore.updateUa(existing.copy(name = name, userAgent = ua, systemDefault = false))
                }
                refresh()
            }
            .setNegativeButton(if (existing == null) R.string.common_cancel else R.string.common_delete, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.let { btn ->
                if (existing == null) {
                    btn.setOnClickListener { dialog.dismiss() }
                } else {
                    btn.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            btn, com.google.android.material.R.attr.colorError
                        )
                    )
                    btn.setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.common_delete)
                            .setMessage(R.string.common_delete_confirm)
                            .setPositiveButton(R.string.common_delete) { _, _ ->
                                ConfigStore.deleteUa(existing.id)
                                dialog.dismiss()
                                refresh()
                            }
                            .setNegativeButton(R.string.common_cancel, null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showLocationDialog(existingId: String?) {
        val existing = existingId?.let { id ->
            ConfigStore.activeProfile().locations.firstOrNull { it.id == id }
        }
        val view = layoutInflater.inflate(R.layout.dialog_edit_location, null)
        val edtName = view.findViewById<EditText>(R.id.edtName)
        val edtLat = view.findViewById<EditText>(R.id.edtLat)
        val edtLng = view.findViewById<EditText>(R.id.edtLng)
        val edtAccuracy = view.findViewById<EditText>(R.id.edtAccuracy)
        existing?.let {
            edtName.setText(it.name)
            edtLat.setText(it.latitude.toString())
            edtLng.setText(it.longitude.toString())
            edtAccuracy.setText(it.accuracyMeters.toInt().toString())
        }
        if (edtAccuracy.text.isNullOrBlank()) edtAccuracy.setText(R.string.default_location_accuracy)

        view.findViewById<View>(R.id.btnFillReal).setOnClickListener {
            withLocationPermission {
                readRealLocation { lat, lng ->
                    edtLat.setText(formatCoord(lat))
                    edtLng.setText(formatCoord(lng))
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.location_add_title else R.string.location_edit_title)
            .setView(view)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val lat = edtLat.text?.toString()?.toDoubleOrNull()
                val lng = edtLng.text?.toString()?.toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    snackbar(R.string.location_invalid)
                    return@setPositiveButton
                }
                val accuracy = edtAccuracy.text?.toString()?.toFloatOrNull()?.coerceIn(1f, 5000f) ?: 10f
                val name = edtName.text?.toString()?.trim().orEmpty()
                    .ifBlank { "${formatCoord(lat)}, ${formatCoord(lng)}" }
                if (existing == null) {
                    ConfigStore.addLocation(
                        LocationEntry(name = name, latitude = lat, longitude = lng, accuracyMeters = accuracy)
                    )
                } else {
                    ConfigStore.updateLocation(
                        existing.copy(name = name, latitude = lat, longitude = lng, accuracyMeters = accuracy)
                    )
                }
                refresh()
            }
            .setNegativeButton(if (existing == null) R.string.common_cancel else R.string.common_delete, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.let { btn ->
                if (existing == null) {
                    btn.setOnClickListener { dialog.dismiss() }
                } else {
                    btn.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            btn, com.google.android.material.R.attr.colorError
                        )
                    )
                    btn.setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.common_delete)
                            .setMessage(R.string.common_delete_confirm)
                            .setPositiveButton(R.string.common_delete) { _, _ ->
                                ConfigStore.deleteLocation(existing.id)
                                dialog.dismiss()
                                refresh()
                            }
                            .setNegativeButton(R.string.common_cancel, null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------- 真实定位 ----------

    private fun withLocationPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingLocationFill = action
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readRealLocation(onResult: (Double, Double) -> Unit) {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) {
                onResult(last.latitude, last.longitude)
                return
            }
        }

        if (providers.isEmpty()) {
            snackbar(R.string.location_unavailable)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        locationHandler = handler

        fun deliver(lat: Double, lng: Double) {
            val listener = activeLocationListener ?: return
            activeLocationListener = null
            locationHandler = null
            handler.removeCallbacksAndMessages(null)
            runCatching { lm.removeUpdates(listener) }
            onResult(lat, lng)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                deliver(location.latitude, location.longitude)
            }
            @Deprecated("deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeLocationListener = listener
        providers.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
        handler.postDelayed({
            activeLocationListener = null
            locationHandler = null
            runCatching { lm.removeUpdates(listener) }
            snackbar(R.string.location_unavailable)
        }, 8000L)
    }

    private fun formatCoord(v: Double): String =
        String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    private fun hostOf(url: String): String =
        runCatching { url.toUri().host ?: url }.getOrDefault(url)

    private fun snackbar(messageRes: Int) {
        _binding?.let { Snackbar.make(it.root, messageRes, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationHandler?.removeCallbacksAndMessages(null)
        _binding = null
    }

    // ---------- 适配器 ----------

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {

        private val items = mutableListOf<Any>()
        private var activeEntryId: String? = null

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<Any>, activeId: String?) {
            items.clear()
            items.addAll(newItems)
            activeEntryId = activeId
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView as MaterialCardView
            val icon: ImageView = itemView.findViewById(R.id.itemIcon)
            val name: TextView = itemView.findViewById(R.id.tvItemName)
            val subtitle: TextView = itemView.findViewById(R.id.tvItemSubtitle)
            val activeMark: ImageView = itemView.findViewById(R.id.imgActive)
            val edit: ImageView = itemView.findViewById(R.id.btnEdit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_entry, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isActive = idOf(item) == activeEntryId
            holder.name.text = nameOf(item)
            holder.subtitle.text = subtitleOf(item)
            holder.icon.setImageResource(
                when (kind) {
                    EntryKind.URL -> R.drawable.ic_link
                    EntryKind.UA -> R.drawable.ic_devices
                    EntryKind.LOCATION -> R.drawable.ic_my_location
                }
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
                val id = idOf(item) ?: return@setOnClickListener
                if (id != activeEntryId) {
                    ConfigStore.setActiveEntry(kind, id)
                    refresh()
                    val msg = when (kind) {
                        EntryKind.URL -> R.string.switch_applied_url
                        EntryKind.UA -> R.string.switch_applied_ua
                        EntryKind.LOCATION -> R.string.switch_applied_location
                    }
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                }
            }
            holder.edit.setOnClickListener { showEditDialog(idOf(item)) }
        }

        override fun getItemCount(): Int = items.size

        private fun idOf(item: Any): String? = when (item) {
            is UrlEntry -> item.id
            is UaEntry -> item.id
            is LocationEntry -> item.id
            else -> null
        }

        private fun nameOf(item: Any): String = when (item) {
            is UrlEntry -> item.name
            is UaEntry -> item.name
            is LocationEntry -> item.name
            else -> ""
        }

        private fun subtitleOf(item: Any): String = when (item) {
            is UrlEntry -> item.url
            is UaEntry -> if (item.systemDefault) getString(R.string.switch_ua_system) else item.userAgent
            is LocationEntry -> getString(
                R.string.location_summary,
                formatCoord(item.latitude),
                formatCoord(item.longitude),
                item.accuracyMeters.toInt()
            )
            else -> ""
        }
    }

    companion object {
        const val ARG_KIND = "entry_kind"

        fun newInstance(kind: EntryKind): ManageEntriesFragment =
            ManageEntriesFragment().apply {
                arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
            }
    }
}
