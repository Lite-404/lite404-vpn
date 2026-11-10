package com.lite404.vpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Путь к API приложения. Порт всегда 443. Имя сервера ищется своим DNS (DoH по IP-адресу, мимо
 * системного DNS — у провайдера он бывает сломан или подменён), соединение идёт на найденный IP,
 * а в TLS (SNI) и в Host передаётся имя — сервер выбирает назначение по SNI. Доверие — по отпечатку
 * сертификата из токена (его проверяет TrustManager внутри [ssl]).
 */
object DirectHttps {
    const val API_DOMAIN = "app.barked.fun"
    const val API_PORT = 443

    /** Старые токены выданы на IP старого сервера (88.151.114.227:2097) — их ведём на домен. */
    private val legacyHosts = setOf("88.151.114.227")

    /** Свой DNS: DoH JSON API Cloudflare и Google по IP-адресу. */
    private val dohUrls = listOf("https://1.1.1.1/dns-query?type=A&name=", "https://8.8.8.8/resolve?type=A&name=")

    private const val MAX_BODY = 8 shl 20

    /** Имя сервера API из адреса токена (порт токена не используется). */
    fun apiHost(addr: String): String {
        val host = splitAddr(addr).first.lowercase()
        return if (host in legacyHosts) API_DOMAIN else host
    }

    /** IPv4-адреса имени через свой DNS; пусто — DoH недоступен. */
    fun resolve(host: String): List<String> {
        for (u in dohUrls) {
            val ips = runCatching {
                val c = URL(u + URLEncoder.encode(host, "UTF-8")).openConnection() as HttpsURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.setRequestProperty("Accept", "application/dns-json")
                try { parseDoh(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
            }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    /** Ответ DoH JSON: записи type 1 (A) из "Answer". */
    fun parseDoh(json: String): List<String> {
        val answer = json.substringAfter("\"Answer\"", "")
        return Regex("""\{[^{}]*\}""").findAll(answer).map { it.value }
            .filter { Regex(""""type"\s*:\s*1\b""").containsMatchIn(it) }
            .mapNotNull { Regex(""""data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"""").find(it)?.groupValues?.get(1) }
            .toList()
    }

    fun splitAddr(addr: String): Pair<String, Int> {
        val i = addr.lastIndexOf(':')
        if (i <= 0 || addr.startsWith("[")) return addr to 443
        return addr.substring(0, i) to (addr.substring(i + 1).toIntOrNull() ?: 443)
    }

    /** POST на [ip]:[port] с SNI и Host = [host]. HTTP/1.0 — ответ без chunked, конец тела = закрытие соединения. */
    fun post(
        ssl: SSLContext, ip: String, port: Int, host: String, path: String,
        headers: Map<String, String>, body: ByteArray, connectTimeout: Int, readTimeout: Int
    ): Pair<Int, String> {
        val raw = Socket()
        try {
            // IP-литерал: getByName не обращается к DNS
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), port), connectTimeout)
            raw.soTimeout = readTimeout
            val s = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            s.use {
                it.startHandshake()
                val req = StringBuilder()
                    .append("POST ").append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: ").append(body.size).append("\r\n")
                headers.forEach { (k, v) -> req.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n") }
                req.append("\r\n")
                val out = it.outputStream
                out.write(req.toString().toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16 shl 10)
                val inp = it.inputStream
                while (true) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BODY) throw IOException("ответ сервера слишком большой")
                }
                return parseResponse(buf.toByteArray())
            }
        } finally {
            runCatching { raw.close() }
        }
    }

    /** Разбор ответа HTTP/1.x: код и тело (Content-Length учитывается, если есть). */
    fun parseResponse(data: ByteArray): Pair<Int, String> {
        var sep = -1
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()) { sep = i; break }
        }
        if (sep < 0) throw IOException("сервер прислал неполный ответ")
        val head = String(data, 0, sep, Charsets.ISO_8859_1).split("\r\n")
        val status = head.first().split(' ')
        if (status.size < 2 || !status[0].startsWith("HTTP/")) throw IOException("сервер прислал непонятный ответ")
        val code = status[1].toIntOrNull() ?: throw IOException("сервер прислал непонятный ответ")
        val headers = head.drop(1).mapNotNull { line ->
            val c = line.indexOf(':')
            if (c <= 0) null else line.substring(0, c).trim().lowercase() to line.substring(c + 1).trim()
        }.toMap()
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true)
            throw IOException("сервер прислал ответ по частям")
        var end = data.size
        headers["content-length"]?.toIntOrNull()?.let { len ->
            if (sep + 4 + len > data.size) throw IOException("ответ сервера оборвался")
            end = sep + 4 + len
        }
        return code to String(data, sep + 4, end - sep - 4, Charsets.UTF_8)
    }
}
