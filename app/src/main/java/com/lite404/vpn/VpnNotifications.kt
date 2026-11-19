package com.lite404.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** The same controls for the core, WDTT transport and the detached paused notice. */
internal object VpnNotifications {
    const val PAUSED_ID = 405
    const val CONTROL_CHANNEL = "vpn_controls"
    fun build(ctx: Context, channel: String, mode: VpnMode, server: String, status: String,
              paused: Boolean = false, controls: Boolean = true): Notification {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(command: String): PendingIntent = PendingIntent.getForegroundService(ctx,
            mode.ordinal * 10 + if (command == VpnControlService.PAUSE) 1 else if (command == VpnControlService.RESUME) 2 else 3,
            Intent(ctx, VpnControlService::class.java).setAction(command)
                .putExtra("mode", mode.name).putExtra("server", server),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(server.ifBlank { tr(if (mode == VpnMode.WDTT) "Резервный VPN" else "Основной VPN") })
            .setContentText(status).setContentIntent(open).setOngoing(!paused)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (controls) builder
            .addAction(0, tr(if (paused) "Продолжить" else "Пауза"), action(if (paused) VpnControlService.RESUME else VpnControlService.PAUSE))
            .addAction(R.drawable.ic_stop, tr("Отключить"), action(VpnControlService.STOP))
        return builder.build()
    }

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CONTROL_CHANNEL, tr("Управление VPN"), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false); setSound(null, null); enableVibration(false) })
    }

    fun clearPaused(ctx: Context) {
        Prefs(ctx).notificationPause = ""
        ctx.getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
    }

    fun serverName(ctx: Context, mode: VpnMode, peer: String = ""): String = runCatching {
        val prefs = Prefs(ctx)
        val config = AppConfig.parse(prefs.configJson, prefs.configAt)
        if (mode == VpnMode.WDTT) config.wdtt.firstOrNull { "${it.host}:${it.port}" == peer }?.name
            ?: config.wdtt.getOrNull(prefs.wdttIndex)?.name.orEmpty()
        else config.xray.getOrNull(prefs.xrayIndex)?.name.orEmpty()
    }.getOrDefault("")
}
