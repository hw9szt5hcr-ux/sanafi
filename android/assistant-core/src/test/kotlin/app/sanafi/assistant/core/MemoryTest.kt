package app.sanafi.assistant.core

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test
    fun `facts are kept in order and rendered one per line`() {
        val memory = Memory()
        assertTrue(memory.remember("اسمه عبدالله"))
        assertTrue(memory.remember("قهوته المفضلة سعودية"))
        assertEquals("اسمه عبدالله\nقهوته المفضلة سعودية", memory.render())
        assertEquals("", Memory().render())
    }

    @Test
    fun `a repeated fact in another spelling and a blank fact are not stored`() {
        val memory = Memory()
        assertTrue(memory.remember("يحب أغاني محمد عبده"))
        assertFalse(memory.remember(" يحب اغاني محمد عبده "))
        assertFalse(memory.remember("   "))
        assertEquals(1, memory.facts.size)
    }

    @Test
    fun `forget removes every fact that mentions the words and reports how many`() {
        val memory = Memory(listOf("قهوته المفضلة سعودية", "يحب القهوة بدون سكر", "اسمه عبدالله"))
        assertEquals(2, memory.forget("قهو"))
        assertEquals(listOf("اسمه عبدالله"), memory.facts)
        assertEquals(0, memory.forget(" "))
        assertEquals(0, memory.forget("سيارة"))
        assertEquals(1, memory.facts.size)
    }

    @Test
    fun `the oldest facts are dropped when the memory is full`() {
        val memory = Memory()
        repeat(20) { i -> memory.remember("حقيقة رقم $i " + "ك".repeat(200)) }
        assertTrue(memory.render().length <= Memory.MAX_CHARS)
        assertTrue(memory.facts.last().startsWith("حقيقة رقم 19 "))
        assertFalse(memory.facts.any { it.startsWith("حقيقة رقم 0 ") })
        assertEquals(14, memory.facts.size)
    }

    @Test
    fun `a long fact is cut and line breaks inside a fact become spaces`() {
        val memory = Memory()
        memory.remember("س".repeat(500))
        memory.remember("سطر أول\nسطر ثاني")
        assertEquals(Memory.MAX_FACT_CHARS, memory.facts[0].length)
        assertEquals("سطر أول سطر ثاني", memory.facts[1])
    }

    @Test
    fun `render and parse round-trip, blank lines are ignored, and clear empties it`() {
        val memory = Memory(listOf("أ", "ب"))
        assertEquals(memory.facts, Memory.parse(memory.render()).facts)
        assertEquals(listOf("x"), Memory.parse("\n\n x \n").facts)
        memory.clear()
        assertTrue(memory.facts.isEmpty())
    }
}

class UsageLedgerTest {
    @Test
    fun `a call the agent never joined costs nothing`() {
        val ledger = UsageLedger()
        ledger.record(null, 5_000, "2026-11")
        assertEquals(0, ledger.minutes("2026-11"))
    }

    @Test
    fun `started minutes are billed whole, the way HUMAIN bills them`() {
        fun billed(seconds: Double): Int = UsageLedger().also { it.record(1_000, 1_000 + (seconds * 1000).toLong(), "m") }.minutes("m")
        assertEquals(1, billed(1.0))
        assertEquals(1, billed(59.9))
        assertEquals(2, billed(60.0))
        assertEquals(3, billed(135.0))
    }

    @Test
    fun `months are kept apart, calls add up, and the ledger survives encode and decode`() {
        val ledger = UsageLedger()
        ledger.record(0, 10_000, "2026-11")
        ledger.record(0, 70_000, "2026-11")
        ledger.record(0, 10_000, "2026-12")
        val copy = UsageLedger.decode(ledger.encode())
        assertEquals(3, copy.minutes("2026-11"))
        assertEquals(1, copy.minutes("2026-12"))
        assertEquals(0, UsageLedger.decode("garbage\n=\nx=y").minutes("2026-11"))
    }

    @Test
    fun `the month key follows the device time zone`() {
        val lateNovemberUtc = java.time.Instant.parse("2026-11-30T22:30:00Z").toEpochMilli()
        assertEquals("2026-12", UsageLedger.monthKey(lateNovemberUtc, ZoneId.of("Asia/Qatar")))
        assertEquals("2026-11", UsageLedger.monthKey(lateNovemberUtc, ZoneId.of("UTC")))
    }
}
