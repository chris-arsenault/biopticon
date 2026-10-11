package io.ahara.biopticon.probe

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.Duration
import kotlin.reflect.KClass
import java.security.MessageDigest
import org.json.JSONObject

class HealthProbes(private val context: Context, private val log: EventLog, private val run: String) {
    suspend fun run(activity: Boolean, alpha: Boolean) {
        val status = HealthConnectClient.getSdkStatus(context)
        log.emit(run, "HC-01", "availability", "health_connect", "sdk_status",
            if (status == HealthConnectClient.SDK_AVAILABLE) "PASS" else "UNSUPPORTED", "SDK_STATUS",
            listOf(Finding("metadata", "sdk_status", count = status.toLong()), Finding("metadata", "compiled_sdk", text = "1.2.0-alpha06")))
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        log.emit(run, "HC-02", "permissions", "health_connect", "get_granted_permissions", "PASS", "OBSERVED",
            granted.sorted().map { Finding("permission", it, flag = true) })
        HealthConnectFeatures::class.java.fields.filter { it.name.startsWith("FEATURE_") && it.type == Int::class.javaPrimitiveType }.forEach { field ->
            runCatching { client.features.getFeatureStatus(field.getInt(null)) }.onSuccess {
                log.emit(run, "HC-22", field.name, "health_connect", "feature_status", "PASS", "OBSERVED", listOf(Finding("capability", field.name, count = it.toLong())))
            }
        }
        val types = body + if (activity) extra else if (alpha) listOf(ExerciseSessionRecord::class) else emptyList()
        for (type in types) {
            val name = type.simpleName!!
            val permission = HealthPermission.getReadPermission(type)
            if (permission !in granted) {
                log.emit(run, "HC-09", name, "health_connect", "read_records", "BLOCKED_PERMISSION", "READ_PERMISSION_NOT_GRANTED", listOf(Finding("permission", permission, flag = false)))
                continue
            }
            for (days in listOf(7L, 30L)) for (pageSize in listOf(1, 25, 100)) {
                read(client, type, days, pageSize, null, alpha)
            }
            if ("android.permission.health.READ_HEALTH_DATA_HISTORY" in granted) read(client, type, 91, 100, null, alpha)
            else log.emit(run, "HC-04", name, "health_connect", "read_history", "BLOCKED_PERMISSION", "HISTORY_NOT_GRANTED")
            changes(client, type)
        }
        if (activity && HealthPermission.getReadPermission(StepsRecord::class) in granted) aggregateSteps(client)
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        log.emit(run, "HC-20", "write-permissions", "health_connect", "manifest_inventory",
            if (permissions.none { it.startsWith("android.permission.health.WRITE_") }) "PASS" else "FAIL", "MANIFEST_INSPECTED")
        val foreground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        log.emit(run, "HC-05", "worker-read-context", "health_connect", "background_context", if (foreground) "INCONCLUSIVE" else "PASS",
            if (foreground) "UI_STILL_FOREGROUND" else "READ_SWEEP_WITH_UI_BACKGROUND",
            listOf(Finding("metadata", "ui_foreground", flag = foreground),
                Finding("permission", "background_read_granted", flag = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" in granted)))
    }

    private suspend fun read(client: HealthConnectClient, type: KClass<out Record>, days: Long, size: Int, origin: String?, alpha: Boolean) {
        val name = type.simpleName!!
        val originAlias = origin?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(6).joinToString("") { byte -> "%02x".format(byte) } }
        val case = "$name.${days}d.page$size${if (originAlias == null) "" else ".origin.$originAlias"}"
        val attempt = java.util.UUID.randomUUID().toString()
        val start = System.nanoTime()
        try {
            withTimeout(20000) {
                val now = Instant.now()
                var token: String? = null
                var pages = 0; var count = 0L
                val origins = linkedSetOf<String>()
                val fields = linkedSetOf<String>()
                val metadata = linkedSetOf<String>()
                val snapshot = linkedMapOf<String, String>()
                do {
                    val response = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now),
                        if (origin == null) emptySet() else setOf(DataOrigin(origin)), pageSize = size, pageToken = token))
                    response.records.forEach { record ->
                        count++
                        origins.add(record.metadata.dataOrigin.packageName)
                        fields.addAll(attributes(record))
                        metadata.add("id.present=${record.metadata.id.isNotEmpty()}")
                        metadata.add("clientRecordId.present=${record.metadata.clientRecordId != null}")
                        metadata.add("device.present=${record.metadata.device != null}")
                        metadata.add("lastModified.present=${record.metadata.lastModifiedTime != Instant.EPOCH}")
                        metadata.add("clientRecordVersion.present=${record.metadata.clientRecordVersion != 0L}")
                        metadata.add("device.manufacturer.present=${record.metadata.device?.manufacturer != null}")
                        metadata.add("device.model.present=${record.metadata.device?.model != null}")
                        val hash: (String) -> String = { value -> MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) } }
                        snapshot[hash(record.metadata.id)] = hash(record.toString())
                        if (record is ExerciseSessionRecord) {
                            fields.add("exerciseSegments:List")
                            metadata.add("segments.present=${record.segments.isNotEmpty()}")
                            record.segments.forEach { segment -> fields.addAll(attributes(segment).map { "segment.$it" }) }
                        }
                    }
                    token = response.pageToken?.takeIf { it.isNotBlank() }; pages++
                } while (token != null && pages < 4)
                val findings = listOf(Finding("counter", "records_read", count = count), Finding("counter", "pages_read", count = pages.toLong()),
                    Finding("metadata", "window_days", count = days), Finding("metadata", "page_size", count = size.toLong()),
                    Finding("metadata", "origin_filter", text = origin ?: "UNFILTERED"),
                    Finding("metadata", "page_limit_reached", flag = token != null)) +
                    fields.map { Finding("attribute", it.substringBefore(':'), text = it.substringAfter(':')) } +
                    origins.map { Finding("metadata", "data_origin", text = it) } + metadata.map { Finding("metadata", it, flag = true) }
                log.emit(run, "HC-03", case, "health_connect", "read_records", if (count == 0L) "NO_DATA" else "PASS", "QUERY_OBSERVED", findings,
                    (System.nanoTime() - start) / 1000000, attempt)
                if (token == null && size == 100) compareSnapshot(case, snapshot)
                if (origin == null && size == 100 && days == 7L) origins.take(3).forEach { read(client, type, days, size, it, alpha) }
            }
        } catch (_: TimeoutCancellationException) { log.emit(run, "HC-06", case, "health_connect", "read_records", "INCONCLUSIVE", "QUERY_TIMEOUT", attempt = attempt) }
        catch (error: CancellationException) { throw error }
        catch (_: SecurityException) { log.emit(run, "HC-24", case, "health_connect", "read_records", "BLOCKED_PERMISSION", "PERMISSION_DENIED", attempt = attempt) }
        catch (error: Exception) { log.emit(run, "HC-03", case, "health_connect", "read_records", "FAIL", "QUERY_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName)), attempt = attempt) }
    }

    private fun compareSnapshot(key: String, snapshot: Map<String, String>) {
        val prefs = context.getSharedPreferences("query-snapshots", Context.MODE_PRIVATE)
        val prior = prefs.getString(key, null)?.let { JSONObject(it) }
        if (prior != null) {
            val previous = prior.keys().asSequence().toSet()
            log.emit(run, "GI-05", key, "health_connect", "compare_query_snapshots", "PASS", "WINDOW_DIFF_NOT_DELETION_PROOF", listOf(
                Finding("counter", "new_identities", count = (snapshot.keys - previous).size.toLong()),
                Finding("counter", "missing_identities", count = (previous - snapshot.keys).size.toLong()),
                Finding("counter", "changed_fingerprints", count = snapshot.count { (id, value) -> id in previous && prior.getString(id) != value }.toLong())))
        }
        prefs.edit().putString(key, JSONObject(snapshot).toString()).apply()
    }

    private suspend fun aggregateSteps(client: HealthConnectClient) {
        try {
            withTimeout(20000) {
                val now = Instant.now()
                val range = TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now)
                var token: String? = null; var pages = 0; var raw = 0L
                do {
                    val response = client.readRecords(ReadRecordsRequest(StepsRecord::class, range, pageSize = 100, pageToken = token))
                    raw += response.records.sumOf { it.count }; token = response.pageToken?.takeIf { it.isNotBlank() }; pages++
                } while (token != null && pages < 4)
                val aggregate = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))
                val total = aggregate[StepsRecord.COUNT_TOTAL]
                log.emit(run, "HC-13", "StepsRecord.7d", "health_connect", "raw_vs_aggregate", if (token != null) "INCONCLUSIVE" else if (total == null) "NO_DATA" else "PASS",
                    "COMPARISON_ONLY", listOf(Finding("capability", "raw_read_complete", flag = token == null), Finding("capability", "aggregate_present", flag = total != null),
                        Finding("capability", "raw_equals_aggregate", flag = raw == total)) + aggregate.dataOrigins.map { Finding("metadata", "aggregate_origin", text = it.packageName) })
            }
        } catch (error: TimeoutCancellationException) { log.emit(run, "HC-13", "StepsRecord.7d", "health_connect", "raw_vs_aggregate", "INCONCLUSIVE", "QUERY_TIMEOUT") }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { log.emit(run, "HC-13", "StepsRecord.7d", "health_connect", "raw_vs_aggregate", "INCONCLUSIVE", "AGGREGATE_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName))) }
    }

    private suspend fun changes(client: HealthConnectClient, type: KClass<out Record>) {
        val prefs = context.getSharedPreferences("changes", Context.MODE_PRIVATE)
        val name = type.simpleName!!
        try {
            val prior = prefs.getString(name, null)
            if (prior != null) {
                val response = client.getChanges(prior)
                log.emit(run, "HC-16", name, "health_connect", "read_changes", "PASS", "CHANGES_OBSERVED", listOf(
                    Finding("counter", "change_count", count = response.changes.size.toLong()),
                    Finding("capability", "token_expired", flag = response.changesTokenExpired),
                    Finding("capability", "has_more", flag = response.hasMore)))
                prefs.edit().putString(name, if (response.changesTokenExpired) null else response.nextChangesToken).apply()
            } else {
                val token = client.getChangesToken(ChangesTokenRequest(setOf(type)))
                prefs.edit().putString(name, token).apply()
                log.emit(run, "HC-16", name, "health_connect", "get_changes_token", "PASS", "TOKEN_CREATED_FOR_NEXT_PASS")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { log.emit(run, "HC-16", name, "health_connect", "changes", "INCONCLUSIVE", "CHANGES_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName))) }
    }

    companion object {
        val body: List<KClass<out Record>> = listOf(WeightRecord::class, BodyFatRecord::class, BoneMassRecord::class, LeanBodyMassRecord::class,
            BodyWaterMassRecord::class, HeightRecord::class, BasalMetabolicRateRecord::class)
        val extra: List<KClass<out Record>> = listOf(ExerciseSessionRecord::class, StepsRecord::class, DistanceRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, SpeedRecord::class, SleepSessionRecord::class, ElevationGainedRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class)
        fun permissions(activity: Boolean, alpha: Boolean = false): Set<String> = (body + if (activity) extra else if (alpha) listOf(ExerciseSessionRecord::class) else emptyList()).map { HealthPermission.getReadPermission(it) }.toSet()
        fun attributes(value: Any): Set<String> = value.javaClass.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name !in setOf("getMetadata", "getTime", "getStartTime", "getEndTime", "getStartZoneOffset", "getEndZoneOffset") }
            .flatMap { method ->
                val name = method.name.removePrefix("get")
                val result = runCatching { method.invoke(value) }
                listOf("$name:${method.returnType.simpleName}", "$name.present:${if (result.isFailure) "ACCESS_FAILED" else if (result.getOrNull() == null) "false" else "true"}")
            }.toSet()
    }
}
