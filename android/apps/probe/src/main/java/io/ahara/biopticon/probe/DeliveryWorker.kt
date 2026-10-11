package io.ahara.biopticon.probe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val log = EventLog(applicationContext)
        val auth = Authentication(applicationContext)
        try {
            val base = URI(auth.api)
            if (base.scheme != "https" || base.host == null || base.userInfo != null) return@withContext Result.failure()
            while (true) {
                val pending = log.records.pending()
                if (pending.isEmpty()) break
                for (row in pending) {
                    val token = auth.token() ?: return@withContext Result.success()
                    val connection = URI("${auth.api}/v1/runs/${row.runId}/events/${row.id}").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "PUT"; connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15000; connection.readTimeout = 15000
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        connection.outputStream.use { it.write(row.json.toByteArray(Charsets.UTF_8)) }
                        when (val code = connection.responseCode) {
                            200 -> log.records.delivery(row.id, 1, "")
                            401, 403 -> {
                                applicationContext.getSharedPreferences("delivery", Context.MODE_PRIVATE).edit().putString("status", "Sign in again: HTTP_$code").apply()
                                return@withContext Result.success()
                            }
                            408, 429 -> return@withContext Result.retry()
                            in 500..599 -> return@withContext Result.retry()
                            else -> { log.records.delivery(row.id, 2, "HTTP_$code"); return@withContext Result.failure() }
                        }
                    } finally { connection.disconnect() }
                }
            }
            applicationContext.getSharedPreferences("delivery", Context.MODE_PRIVATE).edit().putString("status", "Queue delivered").apply()
            Result.success()
        } catch (_: java.io.IOException) { Result.retry() }
        finally { auth.service.dispose(); log.db.close() }
    }

    companion object {
        fun scheduleRecovery(context: Context) {
            val request = PeriodicWorkRequestBuilder<DeliveryRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("status-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("status-delivery", ExistingWorkPolicy.KEEP, request)
        }
    }
}

class DeliveryRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DeliveryWorker.enqueue(applicationContext)
        return Result.success()
    }
}
