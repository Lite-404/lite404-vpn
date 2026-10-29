package com.lite404.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Умная маршрутизация: профиль приходит из панели 3x-ui (настройки подписки, профили happ/incy)
 * через API приложения. Формат — тот же JSON, что в ссылке happ://routing/…:
 *   DirectSites/DirectIp — напрямую, ProxySites/ProxyIp — через VPN, BlockSites/BlockIp — блокировать,
 *   RouteOrder ("block-proxy-direct") — порядок групп, DomainStrategy, DNS (RemoteDNS*, DomesticDNS*, DnsHosts),
 *   Geoipurl/Geositeurl — свои geo-файлы (категории вроде geosite:whitelist есть только в них).
 */
object Routing {
    const val MODE_SMART = "smart"
    const val MODE_OFF = "off"

    private const val GEOIP = "roscom-geoip.dat"
    private const val GEOSITE = "roscom-geosite.dat"
    private const val REFRESH_MS = 24 * 3600 * 1000L

    /** Профиль для режима; null — «всё через VPN» (или на сервере профиль не настроен). */
    fun profile(config: AppConfig?, mode: String): JSONObject? {
        if (mode == MODE_OFF) return null
        val all = config?.routing ?: return null
        return all["happ"] ?: all["incy"]
    }

    /**
     * Скачать geo-файлы профиля (раз в сутки или при смене адреса). Качаем напрямую: приложение само исключено из VPN.
     * true — файлы на месте (свежие или прошлые), можно применять правила.
     */
    suspend fun ensureGeo(ctx: Context, profile: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val dir = XrayCore.geoDir(ctx)
        listOf(GEOIP to profile.optString("Geoipurl"), GEOSITE to profile.optString("Geositeurl")).all { (name, url) ->
            val file = File(dir, name)
            val meta = File(dir, "$name.url")
            val fresh = file.exists() && runCatching { meta.readText() }.getOrNull() == url &&
                System.currentTimeMillis() - file.lastModified() < REFRESH_MS
            if (url.startsWith("https://") && !fresh) runCatching { download(url, file); meta.writeText(url) }
            file.exists() && file.length() > 0
        }
    }

    private fun download(url: String, target: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val tmp = File(target.parentFile, target.name + ".tmp")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (tmp.length() < 1000) throw IllegalStateException("слишком маленький файл")
            tmp.renameTo(target)
        } finally {
            c.disconnect()
        }
    }

    /** geosite:X / geoip:X → категории из скачанных файлов профиля; домены и IP — как есть. */
    private fun mapGeo(values: JSONArray?, ip: Boolean): JSONArray {
        val out = JSONArray()
        if (values == null) return out
        for (i in 0 until values.length()) {
            val v = values.optString(i).trim()
            when {
                v.isEmpty() -> Unit
                !ip && v.startsWith("geosite:") -> out.put("ext:$GEOSITE:" + v.removePrefix("geosite:"))
                ip && v.startsWith("geoip:") -> out.put("ext:$GEOIP:" + v.removePrefix("geoip:"))
                else -> out.put(v)
            }
        }
        return out
    }

    /** Правила профиля в нужном порядке. Теги выходов: proxy / direct / block. */
    fun rules(profile: JSONObject, proxyTag: String): List<JSONObject> {
        val groups = mapOf(
            "block" to Triple("BlockSites", "BlockIp", "block"),
            "proxy" to Triple("ProxySites", "ProxyIp", proxyTag),
            "direct" to Triple("DirectSites", "DirectIp", "direct"),
        )
        val order = profile.optString("RouteOrder", "block-proxy-direct").split("-").filter { it in groups }
            .ifEmpty { listOf("block", "proxy", "direct") }
        val out = mutableListOf<JSONObject>()
        for (name in order) {
            val (sites, ips, tag) = groups.getValue(name)
            val domains = mapGeo(profile.optJSONArray(sites), ip = false)
            if (domains.length() > 0) out += JSONObject().put("type", "field").put("domain", domains).put("outboundTag", tag)
            val ipList = mapGeo(profile.optJSONArray(ips), ip = true)
            if (ipList.length() > 0) out += JSONObject().put("type", "field").put("ip", ipList).put("outboundTag", tag)
        }
        return out
    }

    /** DNS профиля: «удалённый» DoH через VPN по умолчанию, «домашний» DoH напрямую — для сайтов, что идут напрямую. */
    fun dns(profile: JSONObject): JSONObject {
        val remote = profile.optString("RemoteDNSDomain").ifBlank { profile.optString("RemoteDns", "1.1.1.1") }
        val domestic = profile.optString("DomesticDNSDomain").ifBlank { profile.optString("DomesticDns", "77.88.8.8") }
        val servers = JSONArray().put(remote)
        val directDomains = mapGeo(profile.optJSONArray("DirectSites"), ip = false)
        if (directDomains.length() > 0) {
            // https+local:// — запрос идёт мимо маршрутизации, напрямую (как и сами сайты)
            val local = if (domestic.startsWith("https://")) domestic.replaceFirst("https://", "https+local://") else domestic
            servers.put(JSONObject().put("address", local).put("domains", directDomains).put("skipFallback", true))
        }
        val dns = JSONObject().put("servers", servers).put("queryStrategy", "UseIPv4")
        profile.optJSONObject("DnsHosts")?.let { if (it.length() > 0) dns.put("hosts", it) }
        return dns
    }
}
