package com.lite404.vpn

import android.content.Context

/**
 * Российские приложения мимо VPN: сервисы из перечня Минцифры (работают при отключениях мобильного
 * интернета) и крупные российские сервисы — Яндекс, VK, маркетплейсы, магазины, доставка, банки,
 * операторы связи, транспорт. Многие из них плохо работают с иностранного IP, а через VPN он иностранный.
 *
 * При первом запуске найденные приложения ставятся в режим «Все, кроме выбранных»; при следующих
 * запусках добавляются новые установленные. Снятые пользователем вручную обратно не ставятся.
 * Браузеры не берём: через них открывают любые сайты.
 */
object AutoApps {
    /** Пакеты разработчиков целиком (префикс) и отдельные пакеты (точное совпадение, без точки в конце). */
    private val PACKAGES = listOf(
        // Яндекс: поиск, карты, такси (Go), еда, лавка, маркет, музыка, кинопоиск, диск, почта, банк…
        "ru.yandex.", "com.yandex.", "ru.kinopoisk", "ru.beru.android", "ru.foodfox.", "ru.auto.ara",
        // VK: ВКонтакте, мессенджер, видео, музыка, Одноклассники, Mail, Облако, Дзен, RuStore, Max
        "com.vkontakte.", "com.vk.", "ru.vk.", "ru.ok.", "ru.mail.", "ru.zen.", "ru.oneme.", "com.uma.musicvk",
        // маркетплейсы и объявления
        "ru.ozon.", "com.wildberries.", "com.avito.", "ru.hh.", "ru.domclick.", "ru.sbermegamarket.",
        // госсервисы, платежи
        "ru.rostel", "ru.gosuslugi.", "ru.nspk.", "ru.fns.",
        // банки из перечня
        "ru.vtb24.", "ru.alfabank.", "ru.gazprombank.", "ru.psbank.", "ru.mtsbank.",
        // операторы связи
        "ru.mts.", "ru.megafon.", "ru.beeline.", "ru.tele2.", "ru.yota.", "ru.rt.", "ru.motivtelecom.",
        // транспорт, карты, погода
        "ru.rzd.", "ru.aeroflot.", "ru.tutu.", "ru.dublgis.", "com.taxsee.", "ru.citydrive.", "ru.delimobil.", "ru.gismeteo.",
        // магазины и доставка
        "ru.pyaterochka.", "ru.perekrestok.", "ru.x5.", "ru.vprok.", "ru.chizhik.", "ru.tander.", "ru.magnit.",
        "ru.lenta.", "ru.vkusvill.", "ru.auchan.", "ru.okeydostavka.", "ru.dixy.", "ru.petrovich.",
        "ru.detmir.", "ru.samokat.", "ru.instamart", "ru.kuper.", "ru.cdek.", "ru.dostavista.", "ru.dellin.",
        "com.octopod.russianpost.", "ru.russianpost.",
        // еда и рестораны
        "ru.dodopizza.", "ru.burgerking.", "ru.vkusnoitochka.", "com.apegroup.mcdonaldrussia", "ru.rostics.", "ru.kfc.",
        // видео и медиа
        "ru.rutube.", "ru.ivi.", "ru.more.play", "ru.premier.", "ru.okko.", "ru.gpmrnd.", "ru.vitrina.",
    )

    /** Части названий (без пробелов и знаков, «ё» = «е») — для приложений с непредсказуемым пакетом. */
    private val LABELS = listOf(
        "яндекс", "yandex", "кинопоиск", "вконтакте", "одноклассники", "госуслуги", "мессенджермакс",
        "ozon", "озон", "wildberries", "вайлдберриз", "мегамаркет", "авито", "avito", "домклик", "headhunter",
        "пятерочка", "перекресток", "впрок", "чижик", "магнит", "вкусвилл", "ашан", "дикси", "петрович",
        "азбукавкуса", "детскиймир", "самокат", "купер", "сдэк", "cdek", "почтароссии", "достависта", "деловыелинии",
        "вкусноиточка", "rostics", "ростикс", "бургеркинг", "burgerking", "додопицца", "dodopizza", "многолосося",
        "мойбилайн", "билайн", "beeline", "моймтс", "мегафон", "megafon", "ростелеком", "yota", "йота",
        "ржд", "аэрофлот", "aeroflot", "тутуру", "2гис", "2gis", "такси максим", "ситидрайв", "делимобиль", "gismeteo",
        "rutube", "кинопоиск", "okko", "окко", "иви", "premier", "втбонлайн", "альфабанк", "альфаонлайн", "газпромбанк",
        "mirpay", "сбпэй",
    )

    private val BROWSERS = setOf(
        "com.yandex.browser", "com.yandex.browser.lite", "com.yandex.browser.beta", "com.yandex.browser.alpha",
        "ru.yandex.searchplugin", "ru.mail.browser", "com.vk.browser",
    )

    /** Похожие названия, которые не берём. */
    private val NOT_LABELS = listOf("browser", "браузер", "магнитол", "магнитный", "magnitola")

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    private val labelKeys = LABELS.map { norm(it) }.filter { it.length >= 3 }.distinct()

    fun matches(pkg: String, label: String): Boolean {
        if (pkg in BROWSERS || pkg.startsWith("com.yandex.browser")) return false
        if (PACKAGES.any { if (it.endsWith('.')) pkg.startsWith(it) else pkg == it || pkg.startsWith("$it.") }) return true
        val l = norm(label)
        if (l.isEmpty() || NOT_LABELS.any { l.contains(it) }) return false
        return labelKeys.any { k -> if (k.length <= 4) l == k || l.startsWith(k) else l.contains(k) }
    }

    /** Установленные российские приложения (кроме самого себя). Долго — вызывать не в главном потоке. */
    fun find(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { matches(it.packageName, runCatching { it.loadLabel(pm).toString() }.getOrDefault("")) }
            .map { it.packageName }.toSet()
    }

    /**
     * Первый запуск: «Все, кроме выбранных» + найденные приложения (если пользователь ещё не настраивал).
     * Дальше — новые установленные российские приложения добавляются к исключениям.
     * Возвращает, сколько приложений добавлено.
     */
    fun sync(ctx: Context): Int {
        val prefs = Prefs(ctx)
        val found = runCatching { find(ctx) }.getOrElse { return 0 }
        val new = found - prefs.autoAppsSeen
        var added = 0
        if (new.isNotEmpty()) when {
            !prefs.autoAppsDone && prefs.appsMode == "off" -> { prefs.appsMode = "exclude"; prefs.apps = prefs.apps + new; added = new.size }
            prefs.appsMode == "exclude" -> { (new - prefs.apps).let { added = it.size }; prefs.apps = prefs.apps + new }
        }
        prefs.autoAppsSeen = prefs.autoAppsSeen + found
        prefs.autoAppsDone = true
        return added
    }
}
