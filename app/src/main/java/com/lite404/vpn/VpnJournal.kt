package com.lite404.vpn

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object VpnJournal {
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val secrets = Regex("""(?i)\b(token|password|authorization|privatekey|vk_hash(?:es)?|secret|cookie)\s*[:=]\s*[^\s,;]+""")
    private val links = Regex("""(?i)\b(?:https?|vless|vmess|trojan|ss)://\S+""")
    private val credentials = Regex("""\b(?:L404-[\w-]+|[A-Za-z0-9_+/-]{32,}={0,2})\b""")

    fun enabled(ctx: Context, mode: VpnMode) = file(ctx, mode, "enabled").exists()

    fun setEnabled(ctx: Context, mode: VpnMode, enabled: Boolean) {
        val marker = file(ctx, mode, "enabled")
        if (enabled) marker.createNewFile() else marker.delete()
        if (mode == VpnMode.WDTT) TunnelManager.isLoggingEnabled = marker.exists()
    }

    @Synchronized
    fun record(ctx: Context, mode: VpnMode, message: String) {
        if (!enabled(ctx, mode)) return
        val text = credentials.replace(links.replace(secrets.replace(message, "$1=[…]"), "[…]"), "[…]")
            .replace(Regex("[\\r\\n]+"), " ").take(450)
        access(ctx, mode) { journal ->
            val entry = ("${LocalDateTime.now().format(format)}  $text\n").toByteArray()
            if (journal.length() + entry.size > 48 * 1024) {
                val limit = 24 * 1024
                journal.seek((journal.length() - limit).coerceAtLeast(0))
                val tail = ByteArray(minOf(journal.length(), limit.toLong()).toInt())
                journal.readFully(tail)
                val start = tail.indexOf(10.toByte()) + 1
                journal.setLength(0)
                journal.write(tail, start, tail.size - start)
            }
            journal.seek(journal.length())
            journal.write(entry)
        }
    }

    @Synchronized
    fun read(ctx: Context, mode: VpnMode): String {
        if (!file(ctx, mode, "txt").exists()) return ""
        return access(ctx, mode) {
            ByteArray(it.length().toInt()).also(it::readFully).decodeToString()
        }.orEmpty()
    }

    @Synchronized
    fun clear(ctx: Context, mode: VpnMode) {
        access(ctx, mode) { it.setLength(0) }
    }

    private fun file(ctx: Context, mode: VpnMode, suffix: String) =
        File(ctx.filesDir, "journal_${mode.name.lowercase()}.$suffix")

    private inline fun <T> access(ctx: Context, mode: VpnMode, block: (RandomAccessFile) -> T): T? = runCatching {
        RandomAccessFile(file(ctx, mode, "txt"), "rw").use { log ->
            log.channel.lock().use { block(log) }
        }
    }.getOrNull()
}
