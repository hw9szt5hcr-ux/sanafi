package app.sanafi.assistant.core

/**
 * What Sanafi knows about the main driver between trips: short facts, oldest first. HUMAIN keeps
 * nothing between calls, so this text is sent with every call and the agent adds to it itself.
 */
class Memory(facts: List<String> = emptyList()) {
    private val stored = facts.toMutableList()
    val facts: List<String> get() = stored.toList()

    /** False for a blank fact or one already stored in any spelling. */
    fun remember(fact: String): Boolean {
        val clean = fact.replace(Regex("\\s+"), " ").trim().take(MAX_FACT_CHARS).trim()
        if (clean.isEmpty()) return false
        val key = ArabicText.normalize(clean)
        if (stored.any { ArabicText.normalize(it) == key }) return false
        stored += clean
        while (render().length > MAX_CHARS && stored.size > 1) stored.removeAt(0)
        return true
    }

    /** Removes every fact that contains the given words; returns how many were removed. */
    fun forget(query: String): Int {
        val key = ArabicText.normalize(query)
        if (key.isEmpty()) return 0
        val before = stored.size
        stored.removeAll { ArabicText.normalize(it).contains(key) }
        return before - stored.size
    }

    fun clear() = stored.clear()

    /** One fact per line; empty text when nothing is known. */
    fun render(): String = stored.joinToString("\n")

    companion object {
        const val MAX_CHARS = 3000
        const val MAX_FACT_CHARS = 200

        fun parse(text: String): Memory = Memory(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }
}
