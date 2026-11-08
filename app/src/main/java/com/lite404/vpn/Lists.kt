package com.lite404.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Свои списки основного VPN — действуют в обоих режимах маршрутизации:
 *  - готовые списки «напрямую» (категории из geo-файлов профиля сервера: roscom-geosite/geoip);
 *  - свои адреса напрямую и свои адреса через VPN (по одному в строке: домен, IP или подсеть).
 * Свои адреса через VPN важнее всего остального (кроме блокировки рекламы из профиля).
 */
object Lists {
    data class Ready(val id: String, val title: String, val hint: String, val sites: List<String>, val ips: List<String> = emptyList())

    /** Названия и описания — ключи перевода (tr). */
    val READY = listOf(
        Ready("ru-services", "Российские сервисы", "Госуслуги, банки, магазины, доставка, соцсети, операторы связи",
            listOf("geosite:whitelist"), listOf("geoip:whitelist")),
        Ready("ru-all", "Все российские сайты", "Все сайты в зонах .ru, .рф, .su и все российские IP-адреса",
            listOf("domain:ru", "domain:xn--p1ai", "domain:su"), listOf("geoip:direct")),
        Ready("ru-only", "Сайты только для России", "Сайты, которые открываются только с российского IP: часть банков, сервисы проверки IP и др.",
            listOf("geosite:category-ru")),
        Ready("games", "Игры", "Steam, Epic Games, Riot Games, Faceit, Escape from Tarkov, EA",
            listOf("geosite:steam", "geosite:epicgames", "geosite:riot", "geosite:faceit", "geosite:escapefromtarkov", "geosite:origin")),
        Ready("microsoft", "Microsoft", "Обновления Windows, Office, Xbox", listOf("geosite:microsoft")),
        Ready("apple", "Apple", "iCloud, App Store, обновления", listOf("geosite:apple")),
    )

    /** Что выбрал пользователь. */
    data class User(val ready: Set<String> = emptySet(), val direct: String = "", val proxy: String = "") {
        fun isEmpty() = ready.isEmpty() && parse(direct).let { it.first.isEmpty() && it.second.isEmpty() } &&
            parse(proxy).let { it.first.isEmpty() && it.second.isEmpty() }
        /** Нужны ли geo-файлы профиля (готовые списки из категорий). */
        fun needsGeo() = READY.any { it.id in ready && (it.sites + it.ips).any { v -> v.startsWith("geo") } }
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]*:[0-9a-fA-F:.]*(/\d{1,3})?$""")
    private val DOMAIN = Regex("""^[\p{L}\p{N}_*-]+(\.[\p{L}\p{N}_-]+)*$""")

    /** Текст (по строке на адрес) → (домены в формате Xray, IP/подсети). Строки с # — комментарии, лишнее пропускается. */
    fun parse(text: String): Pair<List<String>, List<String>> {
        val domains = mutableListOf<String>()
        val ips = mutableListOf<String>()
        val items = text.lines().flatMap { it.substringBefore('#').split(',', ';', ' ', '\t') }
        for (raw in items) {
            var v = raw.trim().lowercase()
            if (v.isEmpty()) continue
            when {
                v.startsWith("geosite:") || v.startsWith("domain:") || v.startsWith("full:") || v.startsWith("keyword:") -> { domains += v; continue }
                v.startsWith("geoip:") -> { ips += v; continue }
            }
            if (IPV4.matches(v) || (v.contains(':') && IPV6.matches(v))) { ips += v; continue }
            v = v.substringAfter("://").substringBefore('/').substringBefore('?')
            if (IPV4.matches(v) || (v.contains(':') && IPV6.matches(v))) { ips += v; continue }
            v = v.substringBefore(':').removePrefix("*.").trimStart('.').trimEnd('.')
            if (v.contains('.') && DOMAIN.matches(v)) domains += "domain:" + java.net.IDN.toASCII(v, java.net.IDN.ALLOW_UNASSIGNED)
        }
        return domains.distinct() to ips.distinct()
    }

    private fun rule(key: String, values: List<String>, tag: String) =
        JSONObject().put("type", "field").put(key, Routing.mapGeo(JSONArray(values), key == "ip")).put("outboundTag", tag)

    /**
     * Правила пользователя (в порядке важности). [geo] — geo-файлы профиля скачаны: без них категории
     * (geosite:/geoip:) пропускаются, остальное работает.
     */
    fun rules(user: User, proxyTag: String, geo: Boolean): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun add(domains: List<String>, ips: List<String>, tag: String) {
            val d = domains.filter { geo || !it.startsWith("geosite:") }
            val i = ips.filter { geo || !it.startsWith("geoip:") }
            if (d.isNotEmpty()) out += rule("domain", d, tag)
            if (i.isNotEmpty()) out += rule("ip", i, tag)
        }
        parse(user.proxy).let { (d, i) -> add(d, i, proxyTag) }
        parse(user.direct).let { (d, i) -> add(d, i, "direct") }
        val chosen = READY.filter { it.id in user.ready }
        add(chosen.flatMap { it.sites }, chosen.flatMap { it.ips }, "direct")
        return out
    }

    /** Домены, которые идут напрямую (для «домашнего» DNS). */
    fun directDomains(user: User, geo: Boolean): List<String> {
        val chosen = READY.filter { it.id in user.ready }
        return (parse(user.direct).first + chosen.flatMap { it.sites }).filter { geo || !it.startsWith("geosite:") }.distinct()
    }
}
