package io.ahara.biopticon.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PersistenceTest {
    private fun worker(task: () -> Unit) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try { executor.submit(task).get() } finally { executor.shutdownNow() }
    }

    @Test fun outboxSurvivesReopenAndKeepsRelatedFindingsTogether() = worker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("probe.db")
        val first = EventLog(context)
        val run = first.begin()
        first.emit(run, "HC-03", "observed", "health_connect", "read", "PASS", "OBSERVED", listOf(
            Finding("attribute", "new_vendor_attribute", text = "optional_mass"),
            Finding("counter", "records_read", count = 3), Finding("capability", "readable", flag = true)))
        val initial = first.records.pending()
        assertEquals(2, initial.size)
        val event = initial.last()
        assertEquals(3, JSONObject(event.json).getJSONArray("findings").length())
        assertEquals("observed", JSONObject(event.json).getString("case_id"))
        val fixture = java.io.File(requireNotNull(System.getProperty("biopticon.interop.file")))
        requireNotNull(fixture.parentFile).mkdirs()
        fixture.writeText(event.json)
        first.db.close()
        val reopened = EventLog(context)
        try {
            assertEquals(initial.map { it.id }, reopened.records.pending().map { it.id })
            assertEquals(event.json, reopened.records.pending().last().json)
            reopened.records.delivery(event.id, 1, "")
            assertFalse(reopened.records.pending().any { it.id == event.id })
        } finally { reopened.db.close(); context.deleteDatabase("probe.db") }
    }

    @Test fun upgradePreservesEventsPreviouslyBoundToDifferentLogins() = worker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("probe.db")
        context.openOrCreateDatabase("probe.db", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, runId TEXT NOT NULL, json TEXT NOT NULL, summary TEXT NOT NULL, owner TEXT, delivery INTEGER NOT NULL, error TEXT NOT NULL, created INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_events_id ON events (id)")
            old.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY NOT NULL, state TEXT NOT NULL, session TEXT NOT NULL, created INTEGER NOT NULL)")
            old.execSQL("INSERT INTO runs VALUES ('run', 'FINISHED', 'old-process', 1)")
            old.execSQL("INSERT INTO events VALUES (4, 'first', 'run', 'original bytes A', 'first', 'login-a', 0, '', 1)")
            old.execSQL("INSERT INTO events VALUES (9, 'second', 'run', 'original bytes B', 'second', 'login-b', 0, '', 2)")
            old.execSQL("INSERT INTO events VALUES (12, 'delivered', 'run', 'original bytes C', 'third', NULL, 1, '', 3)")
            old.version = 1
        }
        val log = EventLog(context)
        try {
            assertEquals(listOf("first", "second"), log.records.pending().map { it.id })
            assertEquals(listOf("original bytes A", "original bytes B"), log.records.pending().map { it.json })
            assertEquals(listOf(4L, 9L), log.records.pending().map { it.sequence })
            assertEquals(1, log.records.recent().single { it.id == "delivered" }.delivery)
            assertTrue(log.records.interrupted("new-process").isEmpty())
            log.emit(java.util.UUID.randomUUID().toString(), "P-01", "new", "phone", "inventory", "PASS", "OBSERVED")
            assertEquals(13L, log.records.recent().first().sequence)
        } finally { log.db.close(); context.deleteDatabase("probe.db") }
    }

    @Test fun onlyOversizedObservationsSplitAndPreserveEveryFinding() = worker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("probe.db")
        val log = EventLog(context)
        try {
            val run = log.begin()
            val small = (0 until 30).map { Finding("metadata", "field_$it", flag = true) }
            log.emit(run, "HC-03", "small", "health_connect", "read", "PASS", "OBSERVED", small)
            assertEquals(1, log.records.pending().count { JSONObject(it.json).getString("case_id") == "small" })
            val findings = (0 until 12).map { Finding("attribute", "attribute_$it", text = "界".repeat(256)) }
            val attempt = java.util.UUID.randomUUID().toString()
            log.emit(run, "HC-03", "large", "health_connect", "read", "PASS", "OBSERVED", findings, attempt = attempt)
            val rows = log.records.pending().filter { JSONObject(it.json).getString("case_id") == "large" }
            assertTrue(rows.size in 2 until findings.size)
            val names = rows.flatMap { row ->
                assertTrue(row.json.toByteArray(Charsets.UTF_8).size <= 4096)
                val event = JSONObject(row.json)
                assertEquals(attempt, event.getString("attempt_id"))
                val array = event.getJSONArray("findings")
                (0 until array.length()).map { array.getJSONObject(it).getString("name") }
            }
            assertEquals(findings.map { it.name }, names)
            val before = log.records.pending().map { it.id }
            assertThrows(IllegalArgumentException::class.java) {
                log.emit(run, "HC-03", "invalid", "health_connect", "read", "PASS", "OBSERVED",
                    listOf(small.first(), Finding("attribute", "界".repeat(2000), text = "invalid_name")))
            }
            assertThrows(IllegalArgumentException::class.java) {
                log.emit(run, "HC-03", "界".repeat(2000), "health_connect", "read", "PASS", "OBSERVED")
            }
            assertEquals(before, log.records.pending().map { it.id })
        } finally { log.db.close(); context.deleteDatabase("probe.db") }
    }

    @Test fun recoveryFinishesDeadProcessesWithoutInterruptingCurrentRuns() = worker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("probe.db")
        val log = EventLog(context)
        try {
            val live = log.begin()
            val dead = ProbeDatabase.RunRow().apply { id = java.util.UUID.randomUUID().toString(); session = "dead-process" }
            log.records.insertRun(dead)
            log.recover(); log.recover()
            val finishes = log.records.recent().filter { it.summary.contains("CAMPAIGN_INTERRUPTED") }
            assertEquals(1, finishes.size)
            assertEquals(dead.id, finishes.single().runId)
            assertFalse(finishes.any { it.runId == live })
            log.finish(live, "FINISHED")
            assertEquals(0, log.records.interrupted("different-session").size)
        } finally { log.db.close(); context.deleteDatabase("probe.db") }
    }

    @Test fun skippedOrBlockedWriteQuestionsCanBeRecordedWithoutApproval() = worker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("probe.db")
        val log = EventLog(context)
        try {
            val guided = GuidedProbes(context, log)
            assertFalse(guided.assays.any { it.id.startsWith("AW-") || it.id in setOf("P-08", "GA-04", "GA-05", "GA-06", "GI-06", "GI-07", "E2E-05") })
            val assay = guided.assays.single { it.id == "GA-07" }
            assertTrue(assay.writes)
            for (outcome in listOf("SKIPPED", "BLOCKED_AUTH")) guided.observe(assay, "weight", "IMPORT_ACCEPTANCE", outcome, "kg")
            val observations = log.records.pending().map { JSONObject(it.json) }.filter { it.getString("operation") == "guided_observation" }
            assertEquals(listOf("SKIPPED", "BLOCKED_AUTH"), observations.map { it.getString("status") })
            assertEquals(6, log.records.pending().size)
            assertTrue(observations.all { it.getJSONArray("findings").length() == 4 })
            assertTrue(observations.none { it.toString().contains("specific_operation_confirmed") })
        } finally { log.db.close(); context.deleteDatabase("probe.db") }
    }
}
