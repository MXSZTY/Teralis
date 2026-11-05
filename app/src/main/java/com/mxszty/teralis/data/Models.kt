package com.mxszty.teralis.data

import java.util.UUID

/** 一条网址配置 */
data class UrlEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = ""
)

/** 一条 User Agent 配置；[systemDefault] 为 true 时 [userAgent] 被忽略，使用 WebView 系统默认 UA */
data class UaEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val userAgent: String = "",
    val systemDefault: Boolean = false
)

/** 一个虚拟位置（WGS84 经纬度） */
data class LocationEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 10f
)

enum class EntryKind { URL, UA, LOCATION }

/**
 * 一套完整配置文件：网址、UA、虚拟位置及其各自的当前选中项。
 * 多套配置可同时存在，通过 [ConfigStore.activeProfileId] 切换。
 */
data class ConfigProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val urls: List<UrlEntry> = emptyList(),
    val uas: List<UaEntry> = emptyList(),
    val locations: List<LocationEntry> = emptyList(),
    val activeUrlId: String? = null,
    val activeUaId: String? = null,
    val activeLocationId: String? = null,
    /** true = 虚拟位置；false = 设备真实位置 */
    val virtualLocationEnabled: Boolean = true
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 地址栏搜索使用的搜索引擎；[template] 后直接拼接 URL 编码后的关键词 */
enum class SearchEngine(val template: String) {
    BAIDU("https://www.baidu.com/s?wd="),
    BING("https://www.bing.com/search?q="),
    SOGOU("https://www.sogou.com/web?query=")
}

/** 全局设置（不随配置文件切换而变化） */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 可空以兼容旧版配置 JSON（Gson 反序列化时缺失字段为 null）；读取请用 [effectiveSearchEngine] */
    val searchEngine: SearchEngine? = null,
    val dynamicColor: Boolean = true,
    val exitConfirm: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    /** 双指捏合缩放（WebView 内置手势缩放） */
    val pinchZoomEnabled: Boolean = true,
    /** 页面整体缩放百分比，100 为原始大小 */
    val pageZoomPercent: Int = 100,
    val fileAccessEnabled: Boolean = false,
    val autoGrantGeolocation: Boolean = true,
    val deepLocationInject: Boolean = true,
    val clearDataOnExit: Boolean = false,
    /** 可空以兼容旧版配置 JSON；读取请用 [effectiveAutoCheckUpdate] */
    val autoCheckUpdate: Boolean? = null
) {
    /** 当前生效的搜索引擎；旧配置未设置过该字段时回退为必应 */
    val effectiveSearchEngine: SearchEngine get() = searchEngine ?: SearchEngine.BING

    /** 是否启动时自动检查更新；旧配置未设置过该字段时默认开启 */
    val effectiveAutoCheckUpdate: Boolean get() = autoCheckUpdate ?: true
}

/** 配置导入导出信封格式 */
data class ConfigEnvelope(
    val format: String = "",
    val formatVersion: Int = 1,
    val app: String = "",
    val exportedAt: Long = 0L,
    val profile: ConfigProfile? = null
)
