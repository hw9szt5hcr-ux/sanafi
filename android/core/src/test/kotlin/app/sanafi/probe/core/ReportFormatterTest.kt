package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatterTest {
    @Test
    fun `the report lists all eight probes under its header`() {
        val text = ReportFormatter.format(ProbeResults.complete(emptyList()), "P3 Pro / Android 13")
        assertTrue(text.startsWith("SANAFI PROBE REPORT"))
        assertTrue(text.contains("P3 Pro / Android 13"))
        ProbeId.values().forEach { assertTrue("missing ${it.name}", text.contains(it.name)) }
    }

    @Test
    fun `a result line carries status, numbers and detail`() {
        val result = ProbeResult(ProbeId.CALL_LATENCY, Status.PASS, "ok", mapOf("total_ms" to 1800.0), atEpochMs = 0)
        val text = ReportFormatter.format(listOf(result), "dev")
        assertTrue(text.contains("CALL_LATENCY: PASS"))
        assertTrue(text.contains("total_ms=1800.0"))
        assertTrue(text.contains("ok"))
    }

    @Test
    fun `complete fills probes that never ran and keeps the saved ones`() {
        val saved = ProbeResult(ProbeId.MIC, Status.PASS, "ok", atEpochMs = 9)
        val all = ProbeResults.complete(listOf(saved))
        assertEquals(ProbeId.values().toList(), all.map { it.id })
        assertEquals(saved, all[1])
        assertEquals(Status.NOT_RUN, all[0].status)
    }

    @Test
    fun `a result survives being stored as text`() {
        val result = ProbeResult(ProbeId.MIC, Status.FAIL, "صامت \"تماماً\"\nسطر", mapOf("peak_dbfs" to -61.5), atEpochMs = 42)
        assertEquals(result, ProbeCodec.decode(ProbeCodec.encode(result)))
    }

    @Test
    fun `stored text that is damaged decodes to nothing instead of crashing`() {
        assertEquals(null, ProbeCodec.decode("{not json"))
        assertEquals(null, ProbeCodec.decode("""{"id":"NOPE","status":"PASS","detail":"","numbers":{},"at":1}"""))
        assertEquals(null, ProbeCodec.decode(""))
    }
}
