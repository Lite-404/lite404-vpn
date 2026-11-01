package com.lite404.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление приложения из релизов GitHub. В каждом релизе лежит Lite404-VPN-update.json:
 * версия, код версии Android и для каждого файла — прямая ссылка, размер и SHA-256.
 * Скачиваем APK под процессор телефона, проверяем и отдаём установщику Android.
 * Подпись APK та же (ключ с 0.6.0), поэтому приложение обновляется поверх, данные остаются.
 */
object Updater {
    const val MANIFEST_URL = "https://github.com/Lite-404/lite404-vpn/releases/latest/download/Lite404-VPN-update.json"
    private const val UNIVERSAL = "Lite404-VPN-android-universal.apk"

    data class Release(
        val version: String, val code: Int, val notes: String,
        val file: String, val url: String, val size: Long, val sha256: String
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val progress: Float) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** APK для этого телефона (имена — как в прямых ссылках бота). */
    fun apkName(abis: Array<String>): String = when (abis.firstOrNull()) {
        "arm64-v8a" -> "Lite404-VPN-android-arm64.apk"
        "armeabi-v7a" -> "Lite404-VPN-android-armv7.apk"
        else -> UNIVERSAL
    }

    fun parse(json: String, abis: Array<String>): Release {
        val o = JSONObject(json)
        val files = o.getJSONObject("files")
        var name = apkName(abis)
        val f = files.optJSONObject(name) ?: files.getJSONObject(UNIVERSAL).also { name = UNIVERSAL }
        val sha = f.getString("sha256").lowercase()
        require(sha.length == 64 && f.getLong("size") > 0) { "bad manifest" }
        return Release(
            o.getString("version"), o.getInt("android_code"), o.optString("notes"),
            name, f.getString("url"), f.getLong("size"), sha
        )
    }

    fun check() {
        if (job?.isActive == true) return
        _state.value = State.Checking
        job = scope.launch {
            _state.value = try {
                val r = parse(String(get(MANIFEST_URL).use { it.readBytes() }), Build.SUPPORTED_ABIS)
                if (r.code > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate
            } catch (e: Exception) {
                State.Failed(tr("Не удалось проверить обновления: %s", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun download(ctx: Context, r: Release) {
        if (job?.isActive == true) return
        val dir = File(ctx.cacheDir, "update")
        _state.value = State.Downloading(r, 0f)
        job = scope.launch {
            _state.value = try {
                dir.deleteRecursively(); dir.mkdirs()
                val file = File(dir, r.file)
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                get(r.url).use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > r.size) throw IOException(tr("Файл обновления повреждён. Попробуйте ещё раз."))
                            out.write(buf, 0, n); md.update(buf, 0, n)
                            _state.value = State.Downloading(r, done.toFloat() / r.size)
                        }
                    }
                }
                if (!isActive) return@launch
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (done != r.size || sha != r.sha256) {
                    file.delete()
                    throw IOException(tr("Файл обновления повреждён. Попробуйте ещё раз."))
                }
                State.Ready(r, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dir.deleteRecursively()
                State.Failed(tr("Не удалось скачать обновление: %s", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel(ctx: Context) {
        job?.cancel()
        File(ctx.cacheDir, "update").deleteRecursively()
        _state.value = State.Idle
    }

    fun dismiss() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    /** Разрешено ли приложению ставить APK (Android 8+ спрашивает отдельно для каждого приложения). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Открыть установщик Android с новым APK. После установки он предложит «Открыть». */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Остатки прошлого обновления (вызывать при запуске, пока ничего не качается). */
    fun cleanup(ctx: Context) {
        if (_state.value is State.Idle) File(ctx.cacheDir, "update").deleteRecursively()
    }

    /** GET с переходами GitHub на хранилище файлов (https → https). */
    private fun get(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Lite404VPN/${BuildConfig.VERSION_NAME} (Android)")
        if (c.responseCode != 200) {
            val code = c.responseCode
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c.inputStream
    }
}
