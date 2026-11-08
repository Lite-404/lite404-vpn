package com.lite404.vpn

import org.json.JSONArray
import org.json.JSONObject

/** Конфиг сервера из JSON-подписки 3X-UI → конфиг для Xray на телефоне: вход TUN вместо socks/http. */
object XrayConfig {
    const val MTU = 1500

    /**
     * [profile] — профиль умной маршрутизации (null — всё через VPN). Его geo-файлы
     * должны быть уже скачаны ([Routing.ensureGeo]), иначе передавайте null.
     * [lanDirect] — локальная сеть мимо VPN (TUN её и не получает, см. [Lan]).
     * [user] — свои списки (см. [Lists]); [geo] — geo-файлы профиля скачаны (нужны готовым спискам).
     */
    fun build(
        serverJson: String, profile: JSONObject? = null, lanDirect: Boolean = true,
        user: Lists.User = Lists.User(), geo: Boolean = profile != null,
    ): String {
        val root = JSONObject(serverJson)
        root.remove("remarks")
        // журнал каждого соединения (access) на телефоне только тратит процессор и батарею
        root.put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
        // routeOnly=false: в соединение подставляется домен (из TLS/HTTP), и адрес резолвит сервер —
        // ближе к себе и только IPv4 (у нод нет IPv6). Так же работают Happ и v2rayNG.
        root.put("inbounds", JSONArray().put(JSONObject(
            """{"tag":"tun","protocol":"tun","settings":{"name":"xray0","MTU":$MTU,"userLevel":8},
               "sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":false}}"""
        )))
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }.toSet()
        if ("dns-out" !in tags) outs.put(JSONObject("""{"tag":"dns-out","protocol":"dns"}"""))
        if ("ipv6-block" !in tags) outs.put(JSONObject("""{"tag":"ipv6-block","protocol":"blackhole"}"""))
        if ("direct" !in tags) outs.put(JSONObject("""{"tag":"direct","protocol":"freedom"}"""))
        if ("block" !in tags) outs.put(JSONObject("""{"tag":"block","protocol":"blackhole"}"""))
        val proxyTag = outs.optJSONObject(0)?.optString("tag")?.ifBlank { null } ?: "proxy"

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        // DNS-запросы из TUN — во встроенный DNS Xray (он ходит к серверам DNS через прокси)
        rules.put(JSONObject("""{"type":"field","inboundTag":["tun"],"port":"53","outboundTag":"dns-out"}"""))
        // локальная сеть (192.168.x и т.п.) — напрямую; обычно она и так не попадает в туннель (см. Lan)
        if (lanDirect) rules.put(JSONObject().put("type", "field").put("ip", JSONArray(Lan.PRIVATE_CIDRS)).put("outboundTag", "direct"))
        // IPv6 серверы не умеют: отказываем сразу, иначе каждое соединение ждёт неудачной попытки по IPv6
        rules.put(JSONObject("""{"type":"field","ip":["::/0"],"outboundTag":"ipv6-block"}"""))
        val profileRules = profile?.let { Routing.rules(it, proxyTag) } ?: emptyList()
        // блокировка рекламы и торрентов из профиля — первой, затем свои списки, затем остальной профиль
        profileRules.filter { it.optString("outboundTag") == "block" }.forEach { rules.put(it) }
        val userRules = Lists.rules(user, proxyTag, geo)
        userRules.forEach { rules.put(it) }
        profileRules.filter { it.optString("outboundTag") != "block" }.forEach { rules.put(it) }
        if (profile != null) {
            routing.put("domainStrategy", profile.optString("DomainStrategy").ifBlank { "IPIfNonMatch" })
        } else if (userRules.any { it.has("ip") }) {
            routing.put("domainStrategy", "IPIfNonMatch")   // чтобы сайты по имени тоже проверялись по своим IP-спискам
        }
        for (i in 0 until old.length()) {
            val r = stripGeo(old.getJSONObject(i)) ?: continue
            rules.put(r)
        }
        routing.put("rules", rules)
        if (profile != null) {
            root.put("dns", Routing.dns(profile, Lists.directDomains(user, geo)))
        } else {
            val dns = root.optJSONObject("dns") ?: JSONObject("""{"servers":["1.1.1.1","8.8.8.8"]}""").also { root.put("dns", it) }
            dns.put("queryStrategy", "UseIPv4")   // приложения не получают AAAA → сразу идут по IPv4
        }
        return root.toString()
    }

    /** Минимальный конфиг для замера задержки: только исходящее соединение к серверу. */
    fun forPing(serverJson: String): String {
        val root = JSONObject(serverJson)
        val outs = root.optJSONArray("outbounds") ?: JSONArray()
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }
            .firstOrNull { it.optString("tag") == "proxy" } ?: outs.optJSONObject(0)
            ?: throw IllegalArgumentException("в конфиге нет сервера")
        return JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("outbounds", JSONArray().put(proxy)).toString()
    }

    /** Правила подписки с внешними файлами (ext:…) убираем — таких файлов у приложения нет (geoip/geosite без ext: ядро пропускает само, если файла нет). */
    private fun stripGeo(r: JSONObject): JSONObject? {
        var hadMatcher = false
        var left = false
        for (key in listOf("domain", "ip", "source")) {
            val arr = r.optJSONArray(key) ?: continue
            hadMatcher = true
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (!v.startsWith("ext:")) out.put(v)
            }
            if (out.length() == 0) r.remove(key) else { r.put(key, out); left = true }
        }
        if (hadMatcher && !left && !r.has("port") && !r.has("network") && !r.has("protocol") && !r.has("inboundTag")) return null
        return r
    }
}
