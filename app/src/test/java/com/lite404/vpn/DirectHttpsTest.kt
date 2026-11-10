package com.lite404.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class DirectHttpsTest {
    private fun resp(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun parsesStatusAndBody() {
        val (code, body) = DirectHttps.parseResponse(resp("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"a\":\"привет\"}"))
        assertEquals(200, code)
        assertEquals("{\"a\":\"привет\"}", body)
    }

    @Test
    fun respectsContentLength() {
        val (code, body) = DirectHttps.parseResponse(resp("HTTP/1.0 403 Forbidden\r\nContent-Length: 2\r\n\r\n{}junk"))
        assertEquals(403, code)
        assertEquals("{}", body)
    }

    @Test
    fun rejectsBrokenResponses() {
        assertThrows(IOException::class.java) { DirectHttps.parseResponse(resp("HTTP/1.1 200 OK\r\nContent-Length: 10")) }
        assertThrows(IOException::class.java) { DirectHttps.parseResponse(resp("garbage\r\n\r\n")) }
        assertThrows(IOException::class.java) { DirectHttps.parseResponse(resp("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n{}")) }
        assertThrows(IOException::class.java) { DirectHttps.parseResponse(resp("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n")) }
    }

    @Test
    fun apiHostFromToken() {
        assertEquals("app.barked.fun", DirectHttps.apiHost("88.151.114.227:2097"))
        assertEquals("app.barked.fun", DirectHttps.apiHost("APP.barked.fun:2097"))
        assertEquals("app.barked.fun", DirectHttps.apiHost("app.barked.fun"))
    }

    @Test
    fun parsesDohAnswer() {
        val cf = """{"Status":0,"Question":[{"name":"app.barked.fun","type":1}],"Answer":[{"name":"app.barked.fun","type":5,"TTL":300,"data":"x.example."},{"name":"app.barked.fun","type":1,"TTL":300,"data":"5.59.204.154"}]}"""
        assertEquals(listOf("5.59.204.154"), DirectHttps.parseDoh(cf))
        assertEquals(emptyList<String>(), DirectHttps.parseDoh("""{"Status":3,"Question":[{"name":"x","type":1}]}"""))
    }

    /** Живая проверка: LITE404_LIVE=1 ./gradlew :app:testDebugUnitTest. Свой DNS находит каскад; ключ неверный → 403. */
    @Test
    fun liveServerByIpWithSni() {
        assumeTrue(System.getenv("LITE404_LIVE") == "1")
        assertEquals(true, "5.59.204.154" in DirectHttps.resolve("app.barked.fun"))
        val pin = System.getenv("LITE404_PIN") ?: "bcbe2cd027ef71e484db1fa42e57382a45b0c5a985fa3a944d47490837d919f7"
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (got != pin) throw java.security.cert.CertificateException("pin")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val (code, _) = DirectHttps.post(
            ssl, "5.59.204.154", 443, "app.barked.fun", "/app/v1/config",
            mapOf("Authorization" to "Bearer test-wrong-key", "Content-Type" to "application/json"),
            "{}".toByteArray(), 10000, 20000
        )
        assertEquals(403, code)
    }
}
