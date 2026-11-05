package com.mxszty.teralis.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局配置仓库：多套配置文件（URL / UA / 虚拟位置）+ 全局设置。
 * 使用 SharedPreferences + Gson 持久化，进程内单例。
 */
object ConfigStore {

    private const val PREFS_NAME = "teralis_config"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    private const val KEY_SETTINGS = "settings_json"

    const val FORMAT_ID = "teralis-config"
    const val FORMAT_VERSION = 1

    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()

    private var profileList: MutableList<ConfigProfile> = mutableListOf()
    private var activeId: String? = null
    private var cachedSettings: AppSettings? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    // ---------- 加载 / 保存 ----------

    private fun load() {
        val listType = object : TypeToken<MutableList<ConfigProfile>>() {}.type
        profileList = runCatching {
            gson.fromJson<MutableList<ConfigProfile>>(prefs.getString(KEY_PROFILES, null), listType)
        }.getOrNull() ?: mutableListOf()

        if (profileList.isEmpty()) {
            profileList.add(seedProfile())
        }
        activeId = prefs.getString(KEY_ACTIVE_PROFILE, null)
        if (profileList.none { it.id == activeId }) {
            activeId = profileList.first().id
        }
        normalize(activeProfile())
        persistProfiles()

        cachedSettings = runCatching {
            gson.fromJson(prefs.getString(KEY_SETTINGS, null), AppSettings::class.java)
        }.getOrNull() ?: AppSettings()
    }

    private fun seedProfile(): ConfigProfile {
        // 仅预置一个空配置档案，不预置任何网址，首次启动由引导页提示用户添加
        return ConfigProfile(name = "默认配置")
    }

    /** 删除引用失效的选中项，保证至少有一个可用选择 */
    private fun normalize(p: ConfigProfile): ConfigProfile {
        var np = p
        if (np.activeUrlId != null && np.activeUrlId !in np.urls.map { it.id })
            np = np.copy(activeUrlId = np.urls.firstOrNull()?.id)
        // activeUaId 为 null 表示用户显式选择“系统默认 UA”，必须保留
        if (np.activeUaId != null && np.activeUaId !in np.uas.map { it.id })
            np = np.copy(activeUaId = null)
        if (np.activeLocationId != null && np.activeLocationId !in np.locations.map { it.id })
            np = np.copy(activeLocationId = np.locations.firstOrNull()?.id)
        val idx = profileList.indexOfFirst { it.id == p.id }
        if (idx >= 0 && profileList[idx] != np) profileList[idx] = np
        return np
    }

    private fun persistProfiles() {
        prefs.edit {
            putString(KEY_PROFILES, gson.toJson(profileList))
            putString(KEY_ACTIVE_PROFILE, activeId)
        }
    }

    private fun persistSettings() {
        prefs.edit { putString(KEY_SETTINGS, gson.toJson(cachedSettings)) }
    }

    // ---------- 设置 ----------

    val settings: AppSettings
        get() = cachedSettings ?: AppSettings().also { cachedSettings = it }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        cachedSettings = transform(settings)
        persistSettings()
        notifyChanged()
    }

    // ---------- 配置文件 ----------

    val profiles: List<ConfigProfile> get() = profileList.toList()

    val activeProfileId: String? get() = activeId

    fun activeProfile(): ConfigProfile =
        profileList.firstOrNull { it.id == activeId } ?: profileList.first().also { activeId = it.id }

    fun activeUrl(): UrlEntry? = activeProfile().let { p -> p.urls.firstOrNull { it.id == p.activeUrlId } }
    fun activeUa(): UaEntry? = activeProfile().let { p -> p.uas.firstOrNull { it.id == p.activeUaId } }
    fun activeLocation(): LocationEntry? =
        activeProfile().let { p -> p.locations.firstOrNull { it.id == p.activeLocationId } }

    private fun updateProfile(id: String, transform: (ConfigProfile) -> ConfigProfile) {
        val idx = profileList.indexOfFirst { it.id == id }
        if (idx < 0) return
        profileList[idx] = normalize(transform(profileList[idx]))
        persistProfiles()
        notifyChanged()
    }

    fun activateProfile(id: String) {
        if (profileList.none { it.id == id }) return
        activeId = id
        persistProfiles()
        notifyChanged()
    }

    fun addProfile(name: String): ConfigProfile {
        val p = ConfigProfile(name = name.ifBlank { "新建配置" })
        profileList.add(p)
        persistProfiles()
        notifyChanged()
        return p
    }

    fun duplicateProfile(id: String): ConfigProfile? {
        val src = profileList.firstOrNull { it.id == id } ?: return null
        val copy = src.copy(
            id = UUID.randomUUID().toString(),
            name = "${src.name} 副本",
            urls = src.urls.map { it.copy(id = UUID.randomUUID().toString()) },
            uas = src.uas.map { it.copy(id = UUID.randomUUID().toString()) },
            locations = src.locations.map { it.copy(id = UUID.randomUUID().toString()) }
        ).let {
            it.copy(
                activeUrlId = it.urls.getOrNull(src.urls.indexOfFirst { e -> e.id == src.activeUrlId })?.id,
                activeUaId = it.uas.getOrNull(src.uas.indexOfFirst { e -> e.id == src.activeUaId })?.id,
                activeLocationId = it.locations.getOrNull(src.locations.indexOfFirst { e -> e.id == src.activeLocationId })?.id
            )
        }
        profileList.add(copy)
        persistProfiles()
        notifyChanged()
        return copy
    }

    fun renameProfile(id: String, name: String) {
        updateProfile(id) { it.copy(name = name.ifBlank { it.name }) }
    }

    fun deleteProfile(id: String) {
        if (profileList.size <= 1) return
        val wasActive = id == activeId
        profileList.removeAll { it.id == id }
        if (wasActive) activeId = profileList.first().id
        persistProfiles()
        notifyChanged()
    }

    fun setVirtualLocationEnabled(enabled: Boolean) {
        val p = activeProfile()
        updateProfile(p.id) { it.copy(virtualLocationEnabled = enabled) }
    }

    // ---------- 条目增删改查 ----------

    fun setActiveEntry(kind: EntryKind, entryId: String?) {
        val p = activeProfile()
        updateProfile(p.id) {
            when (kind) {
                EntryKind.URL -> it.copy(activeUrlId = entryId)
                EntryKind.UA -> it.copy(activeUaId = entryId)
                EntryKind.LOCATION -> it.copy(activeLocationId = entryId)
            }
        }
    }

    fun addUrl(entry: UrlEntry) = updateProfile(activeProfile().id) { p ->
        val list = p.urls + entry
        p.copy(urls = list, activeUrlId = p.activeUrlId ?: entry.id)
    }

    fun updateUrl(entry: UrlEntry) = updateProfile(activeProfile().id) {
        it.copy(urls = it.urls.map { e -> if (e.id == entry.id) entry else e })
    }

    fun deleteUrl(id: String) = updateProfile(activeProfile().id) {
        it.copy(
            urls = it.urls.filterNot { e -> e.id == id },
            activeUrlId = if (it.activeUrlId == id) null else it.activeUrlId
        )
    }

    fun addUa(entry: UaEntry) = updateProfile(activeProfile().id) { p ->
        p.copy(uas = p.uas + entry, activeUaId = p.activeUaId ?: entry.id)
    }

    fun updateUa(entry: UaEntry) = updateProfile(activeProfile().id) {
        it.copy(uas = it.uas.map { e -> if (e.id == entry.id) entry else e })
    }

    fun deleteUa(id: String) = updateProfile(activeProfile().id) {
        it.copy(
            uas = it.uas.filterNot { e -> e.id == id },
            activeUaId = if (it.activeUaId == id) null else it.activeUaId
        )
    }

    fun addLocation(entry: LocationEntry) = updateProfile(activeProfile().id) { p ->
        p.copy(locations = p.locations + entry, activeLocationId = p.activeLocationId ?: entry.id)
    }

    fun updateLocation(entry: LocationEntry) = updateProfile(activeProfile().id) {
        it.copy(locations = it.locations.map { e -> if (e.id == entry.id) entry else e })
    }

    fun deleteLocation(id: String) = updateProfile(activeProfile().id) {
        it.copy(
            locations = it.locations.filterNot { e -> e.id == id },
            activeLocationId = if (it.activeLocationId == id) null else it.activeLocationId
        )
    }

    // ---------- 导入 / 导出 ----------

    fun exportProfile(id: String): String? {
        val p = profileList.firstOrNull { it.id == id } ?: return null
        val envelope = ConfigEnvelope(
            format = FORMAT_ID,
            formatVersion = FORMAT_VERSION,
            app = "Teralis",
            exportedAt = System.currentTimeMillis(),
            profile = p
        )
        return gson.toJson(envelope)
    }

    /**
     * 解析并导入配置。会为配置与所有条目重新生成 ID，避免与现有配置冲突；
     * 名称冲突时自动追加“（导入）”。
     * @return 导入后的新配置；格式错误返回 null。
     */
    fun importProfile(json: String): ConfigProfile? {
        val parsed = runCatching {
            val envelope = gson.fromJson(json, ConfigEnvelope::class.java)
            envelope?.profile?.let { return@runCatching it }
            // 兼容直接导出裸 ConfigProfile 的情况
            gson.fromJson(json, ConfigProfile::class.java)
        }.getOrNull() ?: return null

        if (parsed.name.isBlank() && parsed.urls.isEmpty() && parsed.uas.isEmpty() && parsed.locations.isEmpty()) {
            return null
        }

        var name = parsed.name.ifBlank { "导入的配置" }
        while (profileList.any { it.name == name }) name += "（导入）"

        val urlMap = HashMap<String, String>()
        val uaMap = HashMap<String, String>()
        val locMap = HashMap<String, String>()
        val urls = parsed.urls
            .filter { it.url.isNotBlank() }
            .map { old ->
                val nid = UUID.randomUUID().toString()
                urlMap[old.id] = nid
                old.copy(id = nid, name = old.name.ifBlank { old.url })
            }
        val uas = parsed.uas
            .filter { it.systemDefault || it.userAgent.isNotBlank() }
            .map { old ->
                val nid = UUID.randomUUID().toString()
                uaMap[old.id] = nid
                old.copy(id = nid, name = old.name.ifBlank { "自定义 UA" })
            }
        val locs = parsed.locations.map { old ->
            val nid = UUID.randomUUID().toString()
            locMap[old.id] = nid
            old.copy(id = nid, name = old.name.ifBlank { "位置 ${old.latitude},${old.longitude}" })
        }

        val imported = ConfigProfile(
            name = name,
            urls = urls,
            uas = uas,
            locations = locs,
            activeUrlId = parsed.activeUrlId?.let { urlMap[it] } ?: urls.firstOrNull()?.id,
            activeUaId = parsed.activeUaId?.let { uaMap[it] } ?: uas.firstOrNull()?.id,
            activeLocationId = parsed.activeLocationId?.let { locMap[it] } ?: locs.firstOrNull()?.id,
            virtualLocationEnabled = parsed.virtualLocationEnabled
        )
        profileList.add(imported)
        persistProfiles()
        notifyChanged()
        return imported
    }
}
