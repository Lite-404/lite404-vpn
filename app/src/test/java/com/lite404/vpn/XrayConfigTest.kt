package com.lite404.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сборка конфига Xray с профилями маршрутизации из панели (resources/routing-*.json — настоящие профили).
 * Готовые конфиги пишутся в build/xray-test/ — их проверяет настоящий Xray: `xray run -test -c ...`.
 */
class XrayConfigTest {
    private val server = """
        {"remarks":"🇩🇪 Германия","log":{"loglevel":"warning"},
         "dns":{"servers":[{"address":"8.8.8.8"}],"queryStrategy":"UseIP","tag":"dns_out"},
         "inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","tag":"mixed"}],
         "outbounds":[
           {"protocol":"vless","tag":"proxy","settings":{"address":"de.barked.fun","port":443,"id":"00000000-0000-0000-0000-000000000001","encryption":"none","flow":"xtls-rprx-vision"},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"de.barked.fun","fingerprint":"firefox","publicKey":"Y6vSOCk7fzuGCpNH0mVBYDOEcyXODbHczfWBMf-KlnI","shortId":"8b3f3d","spiderX":"/"}}},
           {"protocol":"freedom","tag":"direct"},
           {"protocol":"blackhole","tag":"block"}],
         "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}
    """.trimIndent()

    private fun profile(name: String) = JSONObject(javaClass.classLoader!!.getResource("routing-$name.json")!!.readText())

    private fun save(name: String, json: String) {
        val dir = File("build/xray-test").apply { mkdirs() }
        // TUN-вход есть только на Android — для проверки на ПК заменяем его на SOCKS
        val root = JSONObject(json)
        root.put("inbounds", org.json.JSONArray().put(JSONObject("""{"tag":"tun","listen":"127.0.0.1","port":10808,"protocol":"socks"}""")))
        File(dir, "$name.json").writeText(root.toString(1))
    }

    @Test
    fun withoutProfile_lanDirect_ipv6Blocked_rest_proxy() {
        val c = JSONObject(XrayConfig.build(server))
        val rules = c.getJSONObject("routing").getJSONArray("rules")
        assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        assertTrue(rules.getJSONObject(1).getJSONArray("ip").toString().contains("192.168.0.0/16"))
        assertEquals("ipv6-block", rules.getJSONObject(2).getString("outboundTag"))
        assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
        assertEquals("UseIPv4", c.getJSONObject("dns").getString("queryStrategy"))
        save("off", c.toString())
    }

    @Test
    fun withProfiles_rulesFromPanel() {
        for (name in listOf("happ", "incy")) {
            val p = profile(name)
            val c = JSONObject(XrayConfig.build(server, p))
            val text = c.getJSONObject("routing").getJSONArray("rules").toString()
            assertTrue(text.contains("ext:roscom-geosite.dat:whitelist"))
            assertTrue(text.contains("ext:roscom-geoip.dat:private"))
            assertTrue(text.contains("ext:roscom-geosite.dat:torrent"))
            // блок → прокси → прямой, затем общее правило подписки (всё в proxy) — последним
            val rules = c.getJSONObject("routing").getJSONArray("rules")
            assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
            assertEquals("IPIfNonMatch", c.getJSONObject("routing").getString("domainStrategy"))
            assertTrue(c.getJSONObject("dns").toString().contains("https+local://77.88.8.8/dns-query"))
            save(name, c.toString())
        }
    }

    @Test
    fun lanDirectOff_noPrivateRule() {
        val c = JSONObject(XrayConfig.build(server, null, lanDirect = false))
        assertTrue(!c.getJSONObject("routing").getJSONArray("rules").toString().contains("192.168.0.0/16"))
    }

    @Test
    fun userLists_orderAndParsing() {
        val user = Lists.User(
            ready = setOf("ru-services", "games"),
            direct = "https://Example.ru/path\n# комментарий\n203.0.113.0/24, 198.51.100.7\nпример.рф\nмусор!!",
            proxy = "*.youtube.com",
        )
        val (d, i) = Lists.parse(user.direct)
        assertEquals(listOf("domain:example.ru", "domain:xn--e1afmkfd.xn--p1ai"), d)
        assertEquals(listOf("203.0.113.0/24", "198.51.100.7"), i)
        for (name in listOf("happ", "incy")) {
            val c = JSONObject(XrayConfig.build(server, profile(name), user = user))
            val rules = c.getJSONObject("routing").getJSONArray("rules")
            val tags = (0 until rules.length()).map { rules.getJSONObject(it) }
            val firstBlock = tags.indexOfFirst { it.optString("outboundTag") == "block" }
            val userProxy = tags.indexOfFirst { it.toString().contains("domain:youtube.com") }
            val userDirect = tags.indexOfFirst { it.toString().contains("domain:example.ru") }
            val games = tags.indexOfFirst { it.toString().contains("ext:roscom-geosite.dat:steam") }
            val profileProxy = tags.indexOfFirst { it.optString("outboundTag") == "proxy" && it.has("domain") && !it.toString().contains("youtube") }
            assertTrue("$name: $tags", firstBlock in 0 until userProxy && userProxy < userDirect && userDirect < games)
            assertEquals("proxy", tags[userProxy].getString("outboundTag"))
            assertTrue(profileProxy == -1 || profileProxy > games)
            assertTrue(c.getJSONObject("dns").toString().contains("domain:example.ru"))
            save("$name-lists", c.toString())
        }
        // без профиля: свои адреса работают, категории (нужны geo-файлы) пропускаются
        val off = JSONObject(XrayConfig.build(server, null, user = user, geo = false))
        val text = off.getJSONObject("routing").toString()
        assertTrue(text.contains("domain:example.ru") && text.contains("203.0.113.0/24") && !text.contains("ext:"))
        assertEquals("IPIfNonMatch", off.getJSONObject("routing").getString("domainStrategy"))
        val offGeo = JSONObject(XrayConfig.build(server, null, user = Lists.User(ready = setOf("ru-all")), geo = true))
        assertTrue(offGeo.toString().contains("ext:roscom-geoip.dat:direct") && offGeo.toString().contains("domain:xn--p1ai"))
        save("off-lists", offGeo.toString())
    }
}
