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

    /** Режим, который реально применяется (include без выбранных приложений = всё через VPN). */
    fun effectiveAppsMode(): String = if (appsMode == "off" || apps.isEmpty()) "off" else appsMode

    /** Выход из аккаунта: удаляем данные аккаунта, настройки приложений оставляем. */
    fun clear() = p.edit().remove("token").remove("config").remove("config_at")
        .remove("xray_index").remove("wdtt_index").apply()
}
