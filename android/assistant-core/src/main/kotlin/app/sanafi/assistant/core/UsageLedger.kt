package app.sanafi.assistant.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Minutes of HUMAIN calls per month, so the owner sees the bill coming. */
class UsageLedger(minutesByMonth: Map<String, Int> = emptyMap()) {
    private val minutes = minutesByMonth.toMutableMap()

    /**
     * HUMAIN bills the first minute when the agent joins and a new one every 60 seconds after
     * that; a call the agent never joined is free.
     */
    fun record(joinedAtMs: Long?, endedAtMs: Long, monthKey: String) {
        if (joinedAtMs == null) return
        val billed = ((endedAtMs - joinedAtMs).coerceAtLeast(0) / 60_000).toInt() + 1
        minutes[monthKey] = minutes(monthKey) + billed
    }

    fun minutes(monthKey: String): Int = minutes[monthKey] ?: 0

    fun encode(): String = minutes.entries.joinToString("\n") { "${it.key}=${it.value}" }

    companion object {
        fun decode(text: String): UsageLedger = UsageLedger(
            text.lines().mapNotNull { line ->
                val month = line.substringBefore('=', "").trim()
                val count = line.substringAfter('=', "").trim().toIntOrNull()
                if (month.isEmpty() || count == null) null else month to count
            }.toMap(),
        )

        /** "2026-11" for the month the moment falls in, in the device's time zone. */
        fun monthKey(epochMs: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(zone)).toString()
    }
}
