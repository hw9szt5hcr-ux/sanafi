package app.sanafi.probe.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** One plain-text report the owner copies back to us. */
object ReportFormatter {
    fun format(results: List<ProbeResult>, device: String): String = buildString {
        appendLine("SANAFI PROBE REPORT")
        appendLine("device: $device")
        for (r in results) {
            append("${r.id.name}: ${r.status.name}")
            if (r.numbers.isNotEmpty()) append(" | " + r.numbers.entries.joinToString(", ") { "${it.key}=${String.format(java.util.Locale.US, "%.1f", it.value)}" })
            if (r.detail.isNotBlank()) append(" | " + r.detail.replace('\n', ' '))
            appendLine()
        }
    }
}

object ProbeResults {
    /** One entry per probe, in order; probes with nothing saved come back as NOT_RUN. */
    fun complete(saved: List<ProbeResult>): List<ProbeResult> {
        val byId = saved.associateBy { it.id }
        return ProbeId.values().map { byId[it] ?: ProbeResult(it, Status.NOT_RUN, "", atEpochMs = 0) }
    }
}

/** Text form of a result for local storage. */
object ProbeCodec {
    fun encode(r: ProbeResult): String = buildJsonObject {
        put("id", r.id.name)
        put("status", r.status.name)
        put("detail", r.detail)
        put("numbers", JsonObject(r.numbers.mapValues { JsonPrimitive(it.value) }))
        put("at", r.atEpochMs)
    }.toString()

    fun decode(text: String): ProbeResult? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        ProbeResult(
            id = ProbeId.valueOf(o.getValue("id").jsonPrimitive.content),
            status = Status.valueOf(o.getValue("status").jsonPrimitive.content),
            detail = o.getValue("detail").jsonPrimitive.content,
            numbers = o.getValue("numbers").jsonObject.mapValues { it.value.jsonPrimitive.double },
            atEpochMs = o.getValue("at").jsonPrimitive.long,
        )
    } catch (_: Exception) {
        null
    }
}
