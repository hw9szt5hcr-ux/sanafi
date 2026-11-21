package app.sanafi.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorHintsTest {
    private val known = listOf(
        "AUTH_SCOPE_MISSING", "CREDITS_EXHAUSTED", "CONCURRENCY_LIMIT_EXCEEDED",
        "RATE_LIMIT_EXCEEDED", "bad_secret", "network",
    )

    @Test
    fun `every known code has its own hint`() {
        assertEquals(known.size, known.map { ErrorHints.forCode(it) }.toSet().size)
    }

    @Test
    fun `an unknown code still gets a readable hint that names the code`() {
        val hint = ErrorHints.forCode("SOMETHING_NEW")
        assertTrue(hint.contains("SOMETHING_NEW"))
        assertTrue(known.none { ErrorHints.forCode(it) == hint })
    }

    @Test
    fun `the scope hint tells the owner which permission to enable`() {
        assertTrue(ErrorHints.forCode("AUTH_SCOPE_MISSING").contains("agent"))
    }
}
