package com.lite404.vpn

import com.wireguard.config.Config
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class WdttRouterTest {
    @Test fun wireGuardTransportKeepsKeysEndpointAndAddress() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val source = """
            [Interface]
            PrivateKey = $key
            Address = 10.66.0.2/32
            MTU = 1280
            [Peer]
            PublicKey = $key
            Endpoint = 127.0.0.1:9000
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()
        val config = Config.parse(ByteArrayInputStream(source.toByteArray()))
        val outbound = WdttRouter.wireGuardOutbound(config).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", outbound.getString("protocol"))
        val settings = outbound.getJSONObject("settings")
        assertEquals("10.66.0.2/32", settings.getJSONArray("address").getString(0))
        assertEquals(key, settings.getString("secretKey"))
        assertEquals("127.0.0.1:9000", settings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
    }

    @Test fun reserveUsesTheSameDomainRulesAndPriorityAsPrimary() {
        val outbound = """{"outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{}}]}"""
        val user = Lists.User(setOf("ru-all"), "example.org", "full:protected.example.org")
        val config = JSONObject(XrayConfig.build(outbound, user = user, geo = true))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val entries = (0 until rules.length()).map { rules.getJSONObject(it) }
        val explicitProxy = entries.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("protected.example.org") == true }
        val direct = entries.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("domain:example.org") == true }
        assertTrue(explicitProxy >= 0 && direct > explicitProxy)
        assertEquals("proxy", entries[explicitProxy].getString("outboundTag"))
        assertEquals("direct", entries[direct].getString("outboundTag"))
        assertTrue(entries.any { it.optJSONArray("domain")?.toString()?.contains("domain:ru") == true })
    }
}
