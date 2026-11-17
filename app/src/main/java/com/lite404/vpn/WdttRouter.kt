package com.lite404.vpn

import android.content.Context
import com.wireguard.config.Config
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

internal object WdttRouter {
    private data class Settings(val profile: JSONObject?, val user: Lists.User, val geo: Boolean, val lan: Boolean)
    @Volatile private var settings: Settings? = null
    @Volatile var enabled = false
        private set
    @Volatile var prepared = false
        private set

    suspend fun prepare(ctx: Context, cachedOnly: Boolean = false) {
        prepared = false
        val prefs = Prefs(ctx)
        val user = prefs.wdttUserLists()
        enabled = prefs.wdttRoutingMode == Routing.MODE_SMART || !user.isEmpty()
        settings = null
        if (!enabled) { prepared = true; return }
        val config = runCatching { AppConfig.parse(prefs.configJson, prefs.configAt) }.getOrNull()
        val source = Routing.profile(config, Routing.MODE_SMART)
        val profile = source.takeIf { prefs.wdttRoutingMode == Routing.MODE_SMART }
        val needsGeo = profile != null || user.needsGeo()
        val geo = if (!needsGeo) false else if (cachedOnly) Routing.hasGeo(ctx) else
            source?.let { Routing.ensureGeo(ctx, it) } == true
        check(!user.needsGeo() || geo) { tr("Готовые списки недоступны — правила не скачались или не настроены на сервере") }
        check(profile == null || geo) { tr("Не удалось скачать правила маршрутизации") }
        settings = Settings(profile, user, geo, prefs.lanDirectWdtt)
        prepared = true
    }

    fun wireGuardOutbound(config: Config): JSONObject {
        val local = config.`interface`
        val peers = JSONArray()
        for (peer in config.peers) {
            val node = JSONObject().put("publicKey", peer.publicKey.toBase64())
                .put("endpoint", peer.endpoint.orElseThrow { IllegalArgumentException("WireGuard endpoint missing") }.toString())
                .put("allowedIPs", JSONArray(listOf("0.0.0.0/0", "::/0")))
            peer.preSharedKey.ifPresent { node.put("preSharedKey", it.toBase64()) }
            peer.persistentKeepalive.ifPresent { node.put("keepAlive", it) }
            peers.put(node)
        }
        val options = JSONObject().put("secretKey", local.keyPair.privateKey.toBase64())
            .put("address", JSONArray(local.addresses.map { it.toString() }))
            .put("mtu", local.mtu.orElse(1280).coerceAtLeast(1280)).put("peers", peers)
        return JSONObject().put("outbounds", JSONArray().put(
            JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", options)
        ))
    }

    suspend fun start(ctx: Context, config: String) {
        if (settings == null) prepare(ctx, cachedOnly = true)
        val state = checkNotNull(settings)
        check(Prefs(ctx).desiredMode == "wdtt") { tr("Подключение отменено") }
        val parsed = Config.parse(ByteArrayInputStream(config.toByteArray()))
        val built = XrayConfig.build(wireGuardOutbound(parsed).toString(), state.profile, state.lan, state.user, state.geo)
        val request = XrayVpnService.start(ctx, built, tr("Резервный VPN"), VpnMode.WDTT)
        repeat(160) {
            if (Prefs(ctx).desiredMode != "wdtt") {
                stop(ctx)
                error(tr("Подключение отменено"))
            }
            if (XrayVpnService.startFinished(ctx, request)) {
                check(up(ctx)) { XrayVpnService.state(ctx).removePrefix("error:") }
                return
            }
            delay(150)
        }
        stop(ctx)
        error(tr("Не удалось запустить маршрутизацию резервного VPN"))
    }

    fun up(ctx: Context) = XrayVpnService.mode(ctx) == VpnMode.WDTT && XrayVpnService.state(ctx) == "running"

    suspend fun stop(ctx: Context) {
        if (XrayVpnService.mode(ctx) == VpnMode.WDTT) XrayVpnService.stopAndWait(ctx)
    }
}
