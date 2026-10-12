package com.lite404.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Токен из бота: L404-<base64url {"a":"host:port","f":"sha256 сертификата","k":"секрет"}>. */
data class Token(val addr: String, val fp: String, val key: String) {
    companion object {
        fun parse(raw: String): Token? {
            val s = raw.trim().replace("\\s".toRegex(), "")
            if (!s.startsWith("L404-")) return null
            return try {
                val json = String(Base64.decode(s.substring(5), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                val o = JSONObject(json)
                val t = Token(o.getString("a"), o.getString("f").lowercase(), o.getString("k"))
                if (t.addr.isBlank() || t.fp.length != 64 || t.key.isBlank()) null else t
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class WdttServer(val name: String, val host: String, val port: Int, val password: String)
data class XrayServer(val name: String, val config: String)
data class UserInfo(
    val name: String, val enabled: Boolean, val expiresAt: Long,
    val up: Long, val down: Long, val total: Long, val devicesLimit: Int, val devices: Int
)

data class AppConfig(
    val user: UserInfo,
    val vkHash: String,
    val wdtt: List<WdttServer>,
    val xray: List<XrayServer>,
    val error: String?,
    val fetchedAt: Long
) {
    companion object {
        fun parse(raw: String, fetchedAt: Long): AppConfig {
            val o = JSONObject(raw)
            val u = o.getJSONObject("user")
            val user = UserInfo(
                u.optString("name"), u.optBoolean("enabled"),
                u.optLong("expires_at").let { if (it in 1 until 100_000_000_000L) it * 1000 else it }, // сервер шлёт секунды
                u.optLong("up"), u.optLong("down"), u.optLong("total"),
                u.optInt("devices_limit"), u.optInt("devices")
            )
            val wd = o.optJSONArray("wdtt") ?: JSONArray()
            val wdtt = (0 until wd.length()).map {
                val w = wd.getJSONObject(it)
                WdttServer(w.optString("name"), w.optString("host"), w.optInt("port", 56000), w.optString("password"))
            }
            val xr = o.optJSONArray("xray") ?: JSONArray()
            val xray = (0 until xr.length()).map {
                val c = xr.getJSONObject(it)
                XrayServer(c.optString("remarks").ifBlank { tr("Сервер %s", it + 1) }, c.toString())
            }
            return AppConfig(user, o.optString("vk_hash"), wdtt, xray, o.optString("error").ifBlank { null }, fetchedAt)
        }
    }
}

class ApiException(val code: String, message: String) : Exception(message)

object AppApi {
    /** Один и тот же HWID — для подписки 3X-UI и для WDTT (в панели и в боте это одно устройство). */
    @SuppressLint("HardwareIds")
    fun hwid(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** Запрос данных по токену. Сертификат сервера сверяется с отпечатком из токена. */
    fun fetch(ctx: Context, token: Token): String {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (got != token.fp) throw java.security.cert.CertificateException(tr("сертификат сервера не совпадает с токеном"))
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val c = URL("https://${token.addr}/app/v1/config").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ssl.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // доверие — по отпечатку
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${token.key}")
        c.setRequestProperty("X-HWID", hwid(ctx))
        c.setRequestProperty("X-Device-OS", "Android")
        c.setRequestProperty("X-Ver-OS", Build.VERSION.RELEASE)
        c.setRequestProperty("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        c.setRequestProperty("User-Agent", "Lite404VPN/${BuildConfig.VERSION_NAME} (Android)")
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write("{}".toByteArray()) }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val err = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            when {
                code == 403 -> throw ApiException("bad_token", tr("Токен не подходит. Возьмите новый в боте @lite404bot"))
                code == 429 -> throw ApiException("rate_limit", tr("Слишком часто. Попробуйте через минуту"))
                code != 200 -> throw ApiException(err.ifBlank { "http_$code" }, tr("Сервер ответил %s", code))
            }
            return body
        } finally {
            c.disconnect()
        }
    }

    fun errorText(code: String?): String? = when (code) {
        null -> null
        "device_limit" -> tr("Достигнут лимит устройств. Удалите ненужное устройство в боте")
        "expired" -> tr("Подписка закончилась или выключена. Продлите её в боте")
        "json_sub_disabled" -> tr("На сервере выключена JSON-подписка (админу: wdtt doctor)")
        "sub_format" -> tr("Сервер прислал непонятную подписку")
        else -> tr("Ошибка подписки: %s", code)
    }
}
