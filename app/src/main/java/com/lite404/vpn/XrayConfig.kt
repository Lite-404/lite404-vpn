package com.lite404.vpn

import org.json.JSONArray
import org.json.JSONObject

/** Конфиг сервера из JSON-подписки 3X-UI → конфиг для Xray на телефоне: вход TUN вместо socks/http. */
object XrayConfig {
    const val MTU = 1500

    fun build(serverJson: String): String {
        val root = JSONObject(serverJson)
        root.remove("remarks")
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("inbounds", JSONArray().put(JSONObject(
            """{"tag":"tun","protocol":"tun","settings":{"name":"xray0","MTU":$MTU,"userLevel":8},
               "sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":true}}"""
        )))
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        var hasDns = false
        for (i in 0 until outs.length()) if (outs.getJSONObject(i).optString("tag") == "dns-out") hasDns = true
        if (!hasDns) outs.put(JSONObject("""{"tag":"dns-out","protocol":"dns"}"""))

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
        // DNS-запросы из TUN — во встроенный DNS Xray (он ходит к серверам DNS через прокси)
        rules.put(JSONObject("""{"type":"field","inboundTag":["tun"],"port":"53","outboundTag":"dns-out"}"""))
        for (i in 0 until old.length()) {
            val r = stripGeo(old.getJSONObject(i)) ?: continue
            rules.put(r)
        }
        routing.put("rules", rules)
        if (root.optJSONObject("dns") == null) {
            root.put("dns", JSONObject("""{"servers":["1.1.1.1","8.8.8.8"],"queryStrategy":"UseIP"}"""))
        }
        return root.toString()
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
                when {
                    v.startsWith("ext:") -> {}
                    else -> out.put(v)
                }
            }
            if (out.length() == 0) r.remove(key) else { r.put(key, out); left = true }
        }
        if (hadMatcher && !left && !r.has("port") && !r.has("network") && !r.has("protocol") && !r.has("inboundTag")) return null
        return r
    }
}
