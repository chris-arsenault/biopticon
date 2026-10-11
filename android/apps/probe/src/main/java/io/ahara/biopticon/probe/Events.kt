package io.ahara.biopticon.probe

import android.content.Context
import androidx.room.Room
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class Finding(val kind: String, val name: String, val text: String? = null, val count: Long? = null, val flag: Boolean? = null) {
    fun json() = JSONObject().put("kind", kind).put("name", name).apply {
        text?.let { put("text", it.take(256)) }; count?.let { put("count", it) }; flag?.let { put("flag", it) }
    }
}

class EventLog(context: Context) {
    companion object { private val processSession = UUID.randomUUID().toString() }
    val db = Room.databaseBuilder(context.applicationContext, ProbeDatabase::class.java, "probe.db")
        .addMigrations(ProbeDatabase.REMOVE_OWNER).build()
    val records get() = db.records()

    private fun event(runId: String, probe: String, case: String, target: String, operation: String,
              status: String, reason: String, findings: List<Finding> = emptyList(),
              duration: Long = 0, attempt: String = UUID.randomUUID().toString()): ProbeDatabase.EventRow {
        val id = UUID.randomUUID().toString()
        val data = JSONObject().put("schema_version", 1).put("event_id", id).put("run_id", runId)
            .put("attempt_id", attempt).put("build", BuildConfig.VERSION_NAME).put("campaign", "discovery-1")
            .put("probe_id", probe).put("case_id", case).put("target", target).put("operation", operation)
            .put("occurred_at", Instant.now().toString()).put("status", status).put("reason_code", reason)
            .put("duration_ms", duration).put("findings", JSONArray(findings.map { it.json() }))
        val encoded = data.toString()
        return ProbeDatabase.EventRow().apply {
            this.id = id; this.runId = runId; json = encoded
            summary = "$probe · $case · $status · $reason"; created = System.currentTimeMillis()
        }
    }

    fun emit(runId: String, probe: String, case: String, target: String, operation: String,
             status: String, reason: String, findings: List<Finding> = emptyList(), duration: Long = 0,
             attempt: String = UUID.randomUUID().toString()) {
        fun observation(group: List<Finding>) = event(runId, probe, case, target, operation, status, reason, group, duration, attempt)
        val events = mutableListOf<ProbeDatabase.EventRow>()
        var group = emptyList<Finding>()
        var row = observation(group)
        for (finding in findings) {
            val candidate = observation(group + finding)
            if (candidate.json.toByteArray(Charsets.UTF_8).size > 4096 && group.isNotEmpty()) {
                events.add(row)
                group = listOf(finding)
                row = observation(group)
            } else {
                group = group + finding
                row = candidate
            }
        }
        events.add(row)
        require(events.all { it.json.toByteArray(Charsets.UTF_8).size <= 4096 }) { "EVENT_TOO_LARGE" }
        db.runInTransaction { events.forEach { records.insertEvent(it) } }
    }

    fun begin(): String {
        val run = ProbeDatabase.RunRow().apply { id = UUID.randomUUID().toString(); session = processSession; created = System.currentTimeMillis() }
        db.runInTransaction { records.insertRun(run); records.insertEvent(event(run.id, "RUN", "start", "probe", "run", "STARTED", "CAMPAIGN_STARTED")) }
        return run.id
    }

    fun finish(id: String, state: String) {
        val run = ProbeDatabase.RunRow().apply { this.id = id; this.state = state }
        records.finish(run, event(id, "RUN", "finish", "probe", "run", state, "CAMPAIGN_$state"))
    }

    fun recover() { records.interrupted(processSession).forEach { finish(it.id, "INTERRUPTED") } }
}
