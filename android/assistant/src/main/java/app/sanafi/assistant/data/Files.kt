package app.sanafi.assistant.data

import android.content.Context
import app.sanafi.assistant.core.DiagLog
import app.sanafi.assistant.core.Memory
import app.sanafi.assistant.core.UsageLedger
import java.io.File

private fun read(file: File): String = try {
    if (file.exists()) file.readText(Charsets.UTF_8) else ""
} catch (_: Exception) {
    ""
}

private fun write(file: File, text: String) {
    try {
        file.writeText(text, Charsets.UTF_8)
    } catch (_: Exception) {
        // A full or broken disk must not take a call down with it.
    }
}

/** What Sanafi remembers about the driver, kept on the box itself. */
class MemoryFile(context: Context) {
    private val file = File(context.applicationContext.filesDir, "memory.txt")
    fun load(): Memory = Memory.parse(read(file))
    fun save(memory: Memory) = write(file, memory.render())
}

class UsageFile(context: Context) {
    private val file = File(context.applicationContext.filesDir, "usage.txt")
    fun load(): UsageLedger = UsageLedger.decode(read(file))
    fun save(ledger: UsageLedger) = write(file, ledger.encode())
}

class DiagFile(context: Context) {
    private val file = File(context.applicationContext.filesDir, "diag.txt")
    fun load(): DiagLog = DiagLog.decode(read(file))
    fun save(log: DiagLog) = write(file, log.encode())
}
