package app.sanafi.assistant.core

/** A short history of what the calls did, which the owner copies back when something goes wrong. */
class DiagLog(private val capacity: Int = 200) {
    private data class Entry(val atMs: Long, val tag: String, val text: String)

    private val entries = ArrayDeque<Entry>()

    fun add(atMs: Long, tag: String, text: String) {
        entries.addLast(Entry(atMs, flat(tag), flat(text)))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** The header line, then one line per entry, oldest first. */
    fun render(header: String): String = (listOf(header) + entries.map { "${it.atMs} ${it.tag} ${it.text}" }).joinToString("\n")

    fun encode(): String = entries.joinToString("\n") { "${it.atMs}\t${it.tag}\t${it.text}" }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    companion object {
        fun decode(text: String, capacity: Int = 200): DiagLog = DiagLog(capacity).also { log ->
            for (line in text.lines()) {
                val parts = line.split('\t', limit = 3)
                val at = parts.getOrNull(0)?.toLongOrNull() ?: continue
                if (parts.size == 3) log.add(at, parts[1], parts[2])
            }
        }
    }
}
