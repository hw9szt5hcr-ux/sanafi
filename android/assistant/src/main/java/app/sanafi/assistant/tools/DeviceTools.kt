package app.sanafi.assistant.tools

import android.content.Context
import app.sanafi.assistant.core.ToolArgs
import app.sanafi.assistant.core.ToolHandler
import app.sanafi.assistant.core.ToolOutcome
import app.sanafi.assistant.data.MemoryFile
import kotlinx.serialization.json.JsonObject

/** What Sanafi can do on the box when the agent asks: each entry answers one client tool. */
class DeviceTools(context: Context) {
    private val memoryFile = MemoryFile(context)

    fun handlers(): Map<String, ToolHandler> = mapOf(
        "remember" to ToolHandler(::remember),
        "forget" to ToolHandler(::forget),
    )

    /** The app names told to the agent so it can ask for one by its real name. */
    fun appLabels(): List<String> = emptyList()

    private fun remember(args: JsonObject): ToolOutcome {
        val fact = ToolArgs.string(args, "fact") ?: return ToolOutcome.Failed("ناقصني المعلومة")
        val memory = memoryFile.load()
        if (!memory.remember(fact)) return ToolOutcome.Done(say = "أعرفها من قبل")
        memoryFile.save(memory)
        return ToolOutcome.Done(say = "حفظتها")
    }

    private fun forget(args: JsonObject): ToolOutcome {
        val words = ToolArgs.string(args, "fact") ?: return ToolOutcome.Failed("ناقصني المعلومة")
        val memory = memoryFile.load()
        if (memory.forget(words) == 0) return ToolOutcome.Done(say = "ما لقيت شي بهالمعنى")
        memoryFile.save(memory)
        return ToolOutcome.Done(say = "نسيتها")
    }
}
