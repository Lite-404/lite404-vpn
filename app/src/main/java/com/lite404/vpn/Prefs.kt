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

    var xrayIndex: Int
        get() = p.getInt("xray_index", 0)
        set(v) = p.edit().putInt("xray_index", v).apply()

    var wdttIndex: Int
        get() = p.getInt("wdtt_index", 0)
        set(v) = p.edit().putInt("wdtt_index", v).apply()

    /** Туннелирование по приложениям: off — всё через VPN; exclude — выбранные мимо VPN; include — только выбранные через VPN. */
    var appsMode: String
        get() = p.getString("apps_mode", "off") ?: "off"
        set(v) = p.edit().putString("apps_mode", v).apply()

    var apps: Set<String>
        get() = p.getStringSet("apps", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("apps", v).apply()

    /** Какой режим пользователь включил последним: "xray", "wdtt" или "" (выключено). Нужен, чтобы после
     *  того как система убила процесс во сне, восстановить именно его и не мешать другому. */
    var desiredMode: String
        get() = p.getString("desired_mode", "") ?: ""
        set(v) { p.edit().putString("desired_mode", v).commit() }

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
