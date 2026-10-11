package io.ahara.biopticon.probe

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class ProbeRunner(private val context: Context, private val log: EventLog, private val run: String) {
    suspend fun run(activity: Boolean, alpha: Boolean) {
        case("P-01", "platform", "phone", "inventory") {
            listOf(Finding("metadata", "android_api", count = Build.VERSION.SDK_INT.toLong()),
                Finding("metadata", "model_family", text = Build.MODEL), Finding("metadata", "manufacturer", text = Build.MANUFACTURER),
                Finding("metadata", "locale", text = java.util.Locale.getDefault().toLanguageTag()),
                Finding("metadata", "timezone", text = java.time.ZoneId.systemDefault().id))
        }
        case("P-01", "installed-apps", "phone", "query_launchers") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).distinctBy { it.activityInfo.packageName }
                .filter { resolved ->
                    val label = resolved.loadLabel(context.packageManager).toString()
                    val name = resolved.activityInfo.packageName
                    listOf("hume", "fittrack", "garmin", "alphaprogression", "alpha progression", "health connect").any { name.contains(it, true) || label.contains(it, true) }
                }.map {
                val name = it.activityInfo.packageName
                val version = context.packageManager.getPackageInfo(name, 0).versionName ?: "unknown"
                Finding("metadata", name.take(160), text = version)
            }.ifEmpty { listOf(Finding("capability", "visible_target_launchers", flag = false)) }
        }
        case("P-07", "share-interfaces", "phone", "resolve_intents") {
            listOf("text/csv", "application/octet-stream", "application/vnd.ant.fit").map { mime ->
                Finding("counter", "share_handlers:$mime", count = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime), 0).size.toLong())
            }
        }
        try { HealthProbes(context, log, run).run(activity, alpha) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { log.emit(run, "HC-01", "initialization", "health_connect", "initialize", "INCONCLUSIVE", "INITIALIZATION_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName))) }
    }

    private suspend fun case(probe: String, case: String, target: String, operation: String, body: suspend () -> List<Finding>) {
        val started = System.nanoTime()
        try {
            val findings = withTimeout(15000) { body() }
            log.emit(run, probe, case, target, operation, "PASS", "OBSERVED", findings, (System.nanoTime() - started) / 1000000)
        } catch (_: TimeoutCancellationException) { log.emit(run, probe, case, target, operation, "INCONCLUSIVE", "OPERATION_TIMEOUT") }
        catch (error: CancellationException) { throw error }
        catch (error: SecurityException) { log.emit(run, probe, case, target, operation, "BLOCKED_PERMISSION", "PERMISSION_DENIED") }
        catch (error: Exception) { log.emit(run, probe, case, target, operation, "FAIL", "OPERATION_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName))) }
        DeliveryWorker.enqueue(context)
    }
}
