package com.lite404.vpn

import android.content.Context

/** Простое хранилище приложения (токен, последние данные с сервера, выбранные серверы). */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("lite404", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()

    var configJson: String
        get() = p.getString("config", "") ?: ""
        set(v) = p.edit().putString("config", v).apply()

    var configAt: Long
        get() = p.getLong("config_at", 0)
        set(v) = p.edit().putLong("config_at", v).apply()

    fun saveConfig(raw: String, at: Long) {
        p.edit().putString("config", raw).putLong("config_at", at).commit()
    }

    var swipeTabsEnabled: Boolean
        get() = p.getBoolean("swipe_tabs_enabled", true)
        set(v) = p.edit().putBoolean("swipe_tabs_enabled", v).apply()

    var lightweight: Boolean
        get() = p.getBoolean("lightweight", false)
        set(v) = p.edit().putBoolean("lightweight", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    var amoled: Boolean
        get() = p.getBoolean("amoled", false)
        set(v) = p.edit().putBoolean("amoled", v).apply()

    var accentTheme: String
        get() = p.getString("accent_theme", "lime") ?: "lime"
        set(v) = p.edit().putString("accent_theme", v).apply()

    var textScale: Float
        get() = p.getFloat("text_scale", 1f).coerceIn(.85f, 1.25f)
        set(v) = p.edit().putFloat("text_scale", v.coerceIn(.85f, 1.25f)).apply()

    var connectOnOpen: Boolean
        get() = p.getBoolean("connect_on_open", false)
        set(v) = p.edit().putBoolean("connect_on_open", v).apply()

    var connectOnBoot: Boolean
        get() = p.getBoolean("connect_on_boot", false)
        set(v) = p.edit().putBoolean("connect_on_boot", v).apply()

    var lastVpn: VpnMode
        get() = if (p.getString("last_vpn", null) == "WDTT" ||
            (!p.contains("last_vpn") && desiredMode == "wdtt")) VpnMode.WDTT else VpnMode.XRAY
        set(v) = p.edit().putString("last_vpn", v.name).apply()

    var wdttWorkers: Int
        get() = p.getInt("wdtt_workers", WdttController.WORKERS).coerceIn(1, WdttController.WORKERS)
        set(v) = p.edit().putInt("wdtt_workers", v.coerceIn(1, WdttController.WORKERS)).apply()

    var wdttDirect: String
        get() = p.getString("wdtt_direct", "") ?: ""
        set(v) = p.edit().putString("wdtt_direct", v).apply()

    var wdttProxy: String
        get() = p.getString("wdtt_proxy", "") ?: ""
        set(v) = p.edit().putString("wdtt_proxy", v).apply()

    internal var pingMethod: PingMethod
        get() = PingMethod.entries.firstOrNull { it.name == p.getString("ping_method", null) } ?: PingMethod.HTTP_GET
        set(v) = p.edit().putString("ping_method", v.name).apply()

    fun appsFor(mode: String): Set<String> = if (mode == "include") {
        p.getStringSet("apps_include", null)?.toSet() ?: if (appsMode == "include") apps else emptySet()
    } else {
        p.getStringSet("apps_exclude", null)?.toSet() ?: if (appsMode != "include") apps else emptySet()
    }

    fun saveApps(mode: String, selected: Set<String>) {
        val include = if (mode == "include") selected else appsFor("include")
        val exclude = if (mode == "exclude") selected else appsFor("exclude")
        p.edit().putStringSet("apps_include", include).putStringSet("apps_exclude", exclude)
            .putString("apps_mode", mode).putStringSet("apps", selected).apply()
    }

    var xrayIndex: Int
        get() = p.getInt("xray_index", 0)
        set(v) = p.edit().putInt("xray_index", v).apply()

    var wdttIndex: Int
        get() = p.getInt("wdtt_index", 0)
        set(v) = p.edit().putInt("wdtt_index", v).apply()

    /** Туннелирование по приложениям: off — всё через VPN; exclude — выбранные мимо VPN; include — только выбранные через VPN. */
    val appsMode: String
        get() = p.getString("apps_mode", "off") ?: "off"

    val apps: Set<String>
        get() = p.getStringSet("apps", emptySet())?.toSet() ?: emptySet()

    /** Какой режим пользователь включил последним: "xray", "wdtt" или "" (выключено). Нужен, чтобы после
     *  того как система убила процесс во сне, восстановить именно его и не мешать другому. */
    var desiredMode: String
        get() = runCatching { desired.readFully().decodeToString() }.getOrElse { p.getString("desired_mode", "") ?: "" }
        set(v) {
            val output = desired.startWrite()
            try { output.write(v.toByteArray()); desired.finishWrite(output) }
            catch (e: Exception) { desired.failWrite(output); throw e }
            p.edit().putString("desired_mode", v).commit()
        }

    private val desired = android.util.AtomicFile(java.io.File(ctx.applicationContext.filesDir, "vpn_desired"))

    /** Адрес для пинга и проверки соединения (должен отвечать 204/200 без тела). */
    var pingUrl: String
        get() = p.getString("ping_url", DEFAULT_PING_URL)?.takeIf { it.startsWith("http") } ?: DEFAULT_PING_URL
        set(v) = p.edit().putString("ping_url", v).apply()

    /** Пинговать серверы автоматически при открытии вкладки. */
    var pingOnOpen: Boolean
        get() = p.getBoolean("ping_on_open", true)
        set(v) = p.edit().putBoolean("ping_on_open", v).apply()

    /** Проверять, что интернет через VPN работает, сразу после подключения. */
    var testAfterConnect: Boolean
        get() = p.getBoolean("test_after_connect", true)
        set(v) = p.edit().putBoolean("test_after_connect", v).apply()

    /** Маршрутизация основного VPN: "smart" — правила с сервера, "off" — всё через VPN. */
    var routingMode: String
        get() = if (p.getString("routing_mode", Routing.MODE_SMART) == Routing.MODE_OFF) Routing.MODE_OFF else Routing.MODE_SMART
        set(v) = p.edit().putString("routing_mode", v).apply()

    /** Свои списки основного VPN (см. [Lists]): готовые списки «напрямую», свои адреса напрямую и через VPN. */
    var readyLists: Set<String>
        get() = p.getStringSet("ready_lists", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("ready_lists", v).apply()

    var customDirect: String
        get() = p.getString("custom_direct", "") ?: ""
        set(v) = p.edit().putString("custom_direct", v).apply()

    var customProxy: String
        get() = p.getString("custom_proxy", "") ?: ""
        set(v) = p.edit().putString("custom_proxy", v).apply()

    fun userLists() = Lists.User(readyLists, customDirect, customProxy)

    var wdttRoutingMode: String
        get() = p.getString("wdtt_routing", Routing.MODE_OFF) ?: Routing.MODE_OFF
        set(v) = p.edit().putString("wdtt_routing", v).apply()

    var wdttReadyLists: Set<String>
        get() = p.getStringSet("wdtt_ready_lists", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("wdtt_ready_lists", v).apply()

    fun wdttUserLists() = Lists.User(wdttReadyLists, wdttDirect, wdttProxy)

    /** Локальная сеть (192.168.x.x и др.) мимо VPN — отдельно для основного и резервного VPN. */
    var lanDirectXray: Boolean
        get() = p.getBoolean("lan_direct_xray", true)
        set(v) = p.edit().putBoolean("lan_direct_xray", v).apply()

    var lanDirectWdtt: Boolean
        get() = p.getBoolean("lan_direct_wdtt", true)
        set(v) = p.edit().putBoolean("lan_direct_wdtt", v).apply()

    /** Предлагали ли уже снять ограничение батареи (чтобы не спрашивать каждый раз). */
    var batteryAsked: Boolean
        get() = p.getBoolean("battery_asked", false)
        set(v) = p.edit().putBoolean("battery_asked", v).apply()

    companion object {
        const val DEFAULT_PING_URL = "https://www.gstatic.com/generate_204"
        val PING_URLS = listOf(
            DEFAULT_PING_URL,
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
        )
    }

    /** Режим, который реально применяется (include без выбранных приложений = всё через VPN). */
    fun effectiveAppsMode(): String = if (appsMode == "off" || apps.isEmpty()) "off" else appsMode

    /** Выход из аккаунта: удаляем данные аккаунта, настройки приложений оставляем. */
    fun clear() = p.edit().remove("token").remove("config").remove("config_at")
        .remove("xray_index").remove("wdtt_index").apply()
}
