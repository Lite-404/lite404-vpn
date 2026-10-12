package com.lite404.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись последнего сбоя в файл, чтобы пользователь мог скопировать его в настройках
 * («Настройки → Последний сбой») и прислать разработчику.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var appContext: Context? = null

    fun install(ctx: Context, process: String) {
        appContext = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { save(e, "вылет, процесс $process, поток ${thread.name}") }
            previous?.uncaughtException(thread, e)
        }
    }

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Сохранить ошибку (и фатальную, и перехваченную). */
    fun save(e: Throwable, where: String) {
        val ctx = appContext ?: return
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("Lite 404 VPN ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("$time — $where")
                appendLine()
                append(sw.toString().take(12_000))
            }
            file(ctx).writeText(text)
        } catch (t: Throwable) {
            Log.e("CrashLog", "не удалось записать сбой", t)
        }
    }

    fun read(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }
}
