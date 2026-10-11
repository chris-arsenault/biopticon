package io.ahara.biopticon.probe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException

class CampaignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val log = EventLog(applicationContext)
        log.recover()
        val run = log.begin()
        var state = "FINISHED"
        try {
            ProbeRunner(applicationContext, log, run).run(inputData.getBoolean("activity", false), inputData.getBoolean("alpha", false))
            Result.success()
        } catch (error: CancellationException) { state = "INTERRUPTED"; throw error }
        catch (error: Exception) {
            state = "INTERRUPTED"
            log.emit(run, "RUN", "runner", "probe", "execute", "FAIL", "RUNNER_EXCEPTION", listOf(Finding("error", "class", text = error.javaClass.simpleName)))
            Result.failure()
        } finally {
            withContext(NonCancellable) { log.finish(run, state); DeliveryWorker.enqueue(applicationContext); log.db.close() }
        }
    }
    companion object {
        fun enqueue(context: Context, activity: Boolean, alpha: Boolean) {
            val request = OneTimeWorkRequestBuilder<CampaignWorker>()
                .setInputData(workDataOf("activity" to activity, "alpha" to alpha)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("campaign", ExistingWorkPolicy.KEEP, request)
        }
    }
}
