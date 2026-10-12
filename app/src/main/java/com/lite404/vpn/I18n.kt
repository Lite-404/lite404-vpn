package com.lite404.vpn

import android.content.Context
import java.util.Locale

/**
 * Простая локализация: ключ — русская строка, переводы лежат в assets/i18n/<код>.tsv
 * (строка «русский\tперевод»). Если перевода нет — показываем русский текст.
 */
object I18n {
    /** Код языка → название на этом языке. Пустой код = как в системе. */
    val languages: List<Pair<String, String>> = listOf(
        "ru" to "Русский",
        "en" to "English",
        "uk" to "Українська",
        "kk" to "Қазақша",
        "uz" to "Oʻzbekcha",
        "tr" to "Türkçe",
        "fa" to "فارسی",
        "ar" to "العربية",
        "zh" to "中文",
        "es" to "Español",
        "pt" to "Português",
        "de" to "Deutsch",
        "fr" to "Français",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
    )
    private val supported = languages.map { it.first }.toSet()
    private val rtlLanguages = setOf("fa", "ar")

    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile var lang: String = "ru"
        private set

    val isRtl: Boolean get() = lang in rtlLanguages

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("lite404", Context.MODE_PRIVATE)

    /** Выбранный пользователем язык ("" — как в системе). */
    fun chosen(ctx: Context): String = prefs(ctx).getString("lang", "") ?: ""

    fun setChosen(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).commit()
        init(ctx)
    }

    /** Язык телефона, приведённый к поддерживаемому. */
    fun systemLang(): String {
        val l = Locale.getDefault().language.lowercase()
        return when {
            l in supported -> l
            l == "iw" || l == "he" -> "en"
            l in setOf("be", "ky", "tg", "hy", "az", "ka", "tk", "mo") -> "ru"
            else -> "en"
        }
    }

    fun init(ctx: Context) {
        val c = chosen(ctx).takeIf { it in supported } ?: systemLang()
        lang = c
        map = if (c == "ru") emptyMap() else load(ctx, c)
    }

    private fun load(ctx: Context, code: String): Map<String, String> = runCatching {
        ctx.applicationContext.assets.open("i18n/$code.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    fun get(key: String): String = map[key] ?: key
}

/** Перевести строку. %s заменяются аргументами по порядку. */
fun tr(key: String, vararg args: Any?): String {
    var s = I18n.get(key)
    for (a in args) {
        val i = s.indexOf("%s")
        if (i < 0) break
        s = s.substring(0, i) + a.toString() + s.substring(i + 2)
    }
    return s
}
