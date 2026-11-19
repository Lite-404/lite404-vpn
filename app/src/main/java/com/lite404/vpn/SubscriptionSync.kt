package com.lite404.vpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object SubscriptionSync {
    const val INTERVAL = 2 * 60 * 60 * 1000L
    private const val FLEX = 15 * 60 * 1000L
    private const val JOB_ID = 40402
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing?.intervalMillis != INTERVAL || existing.flexMillis != FLEX) scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(app, SubscriptionRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(INTERVAL, FLEX)
                .setPersisted(true).build())
        scope.launch {
            while (isActive) {
                runCatching { refresh(app, INTERVAL) }
                delay(5 * 60_000L)
            }
        }
    }

    suspend fun refresh(ctx: Context, maxAge: Long = 30_000): Boolean = mutex.withLock {
        val prefs = Prefs(ctx)
        val rawToken = prefs.token
        val token = Token.parse(rawToken) ?: return@withLock false
        val now = System.currentTimeMillis()
        if (now >= prefs.configAt && now - prefs.configAt < maxAge && prefs.configJson.isNotBlank()) return@withLock false
        val raw = withContext(Dispatchers.IO) { AppApi.fetch(ctx, token) }
        AppConfig.parse(raw, now) // don't replace a usable cache with an invalid response
        if (prefs.token != rawToken) return@withLock false
        prefs.saveConfig(raw, System.currentTimeMillis())
        true
    }
}

class SubscriptionRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            val failed = runCatching { SubscriptionSync.refresh(applicationContext) }.isFailure
            if (isActive) jobFinished(params, failed)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
