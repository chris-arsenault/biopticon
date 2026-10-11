package io.ahara.biopticon.probe

import android.content.Context
import org.json.JSONArray
import java.util.UUID

data class Assay(val id: String, val question: String, val mode: String, val route: String, val optional: Boolean, val writes: Boolean, val implementation: String, val prerequisite: String)

class GuidedProbes(context: Context, private val log: EventLog) {
    val assays: List<Assay> = JSONArray(context.assets.open("assays.json").bufferedReader().use { it.readText() }).let { array ->
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Assay(it.getString("id"), it.getString("question"), it.getString("mode"), it.getString("route"), it.getBoolean("optional"), it.getBoolean("account_write"), it.getString("implementation"), it.getString("prerequisite"))
        } }
    }

    fun observe(assay: Assay, attribute: String, stage: String, outcome: String, units: String) {
        require(attribute.length in 1..128 && attribute.none { it.isISOControl() })
        require(stage in setOf("SOURCE_DISPLAY", "EXPORT", "AUTHENTICATION", "IMPORT_ACCEPTANCE", "STORAGE", "DISPLAY", "READBACK", "DEVICE_RECEIPT"))
        require(outcome in setOf("PASS", "FAIL", "BLOCKED_AUTH", "BLOCKED_PERMISSION", "NO_DATA", "UNSUPPORTED", "INCONCLUSIVE", "SKIPPED"))
        require(units.length <= 64 && units.none { it.isISOControl() })
        val run = log.begin()
        val target = when (assay.id.substringBefore('-')) { "HM" -> "hume"; "GA", "GS", "GW" -> "garmin"; "AP" -> "alpha"; "HC" -> "health_connect"; else -> "phone" }
        log.emit(run, assay.id, "owner-observation", target, "guided_observation", outcome,
            "OWNER_OBSERVED", listOf(Finding("metadata", "observation_method", text = "owner_ui_or_manual_export"),
                Finding("attribute", attribute, text = stage), Finding("metadata", "question_route", text = assay.route)) +
                if (units.isBlank()) emptyList() else listOf(Finding("attribute", "$attribute.units", text = units)), attempt = UUID.randomUUID().toString())
        log.finish(run, "FINISHED")
    }
}
