package com.lite404.vpn

import org.json.JSONArray
import org.json.JSONObject

/** Конфиг сервера из JSON-подписки 3X-UI → конфиг для Xray на телефоне: вход TUN вместо socks/http. */
object XrayConfig {
    const val MTU = 1500

    fun build(serverJson: String): String {
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

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        // DNS-запросы из TUN — во встроенный DNS Xray (он ходит к серверам DNS через прокси)
        rules.put(JSONObject("""{"type":"field","inboundTag":["tun"],"port":"53","outboundTag":"dns-out"}"""))
        // IPv6 серверы не умеют: отказываем сразу, иначе каждое соединение ждёт неудачной попытки по IPv6
        rules.put(JSONObject("""{"type":"field","ip":["::/0"],"outboundTag":"ipv6-block"}"""))
        for (i in 0 until old.length()) {
            val r = stripGeo(old.getJSONObject(i)) ?: continue
            rules.put(r)
        }
        routing.put("rules", rules)
        val dns = root.optJSONObject("dns") ?: JSONObject("""{"servers":["1.1.1.1","8.8.8.8"]}""").also { root.put("dns", it) }
        dns.put("queryStrategy", "UseIPv4")   // приложения не получают AAAA → сразу идут по IPv4
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

    /** geoip.dat и geosite.dat встроены в ядро; правила с внешними файлами (ext:…) убираем — таких файлов нет. */
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
