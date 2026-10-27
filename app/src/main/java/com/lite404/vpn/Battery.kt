package com.lite404.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Ограничение батареи (Doze). Пока оно действует, Android при выключенном экране отрезает приложению сеть
 * и может убить процесс — VPN «засыпает» и после разблокировки долго переподключается.
 */
object Battery {
    fun isUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Системный диалог «Разрешить работу в фоне»; если его нет у прошивки — список настроек батареи. */
    @SuppressLint("BatteryLife")
    fun request(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (i in listOf(direct, list)) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
