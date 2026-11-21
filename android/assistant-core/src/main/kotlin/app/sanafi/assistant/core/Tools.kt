package app.sanafi.assistant.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What happens to the call after a tool has answered. */
enum class After { KEEP_TALKING, END_CALL }

sealed interface ToolOutcome {
    /**
     * [say] is the sentence suggested to the agent; [data] is extra JSON for it; [thenAct] runs
     * once, after the call has ended. A handler that can act at once (opening a screen) does so
     * itself before returning and leaves [thenAct] null.
     */
    data class Done(
        val say: String? = null,
        val data: JsonObject? = null,
        val after: After = After.KEEP_TALKING,
        val thenAct: (() -> Unit)? = null,
    ) : ToolOutcome

    /** [reason] is a sentence the agent can say to the driver. */
    data class Failed(val reason: String) : ToolOutcome
}

fun interface ToolHandler {
    suspend fun run(args: JsonObject): ToolOutcome
}

data class ToolResult(val tool: String, val replyJson: String, val outcome: ToolOutcome, val tookMs: Long)

/** Reads tool arguments the agent sent; anything that is not a usable string reads as null. */
object ToolArgs {
    fun string(args: JsonObject, name: String): String? {
        val value = args[name] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.content.trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Answers the agent's `client_tool_call` RPC. The payload is `{"tool": "...", "arguments": {...}}`;
 * the reply is the JSON string HUMAIN expects: the result, or `{"ok":false,"error":"..."}`.
 */
class ToolRouter(private val handlers: Map<String, ToolHandler>, private val nowMs: () -> Long) {
    /** Never throws, whatever the payload or the handler does. */
    suspend fun handle(payload: String): ToolResult {
        val startedAt = nowMs()
        val request = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val tool = (request?.get("tool") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val outcome = when {
            tool.isEmpty() -> ToolOutcome.Failed("ما فهمت الطلب")
            tool !in handlers -> ToolOutcome.Failed("ما عندي أداة بهالاسم: $tool")
            else -> try {
                handlers.getValue(tool).run(request?.get("arguments") as? JsonObject ?: JsonObject(emptyMap()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ToolOutcome.Failed("صار خطأ وأنا أنفذ الطلب")
            }
        }
        return ToolResult(tool, encode(outcome), outcome, nowMs() - startedAt)
    }

    private fun encode(outcome: ToolOutcome): String {
        val reply = when (outcome) {
            is ToolOutcome.Failed -> failure(outcome.reason)
            is ToolOutcome.Done -> when {
                outcome.say == null && outcome.data == null -> """{"ok":true}"""
                else -> buildJsonObject {
                    outcome.say?.let { put("response", it) }
                    outcome.data?.forEach { (key, value) -> put(key, value) }
                }.toString()
            }
        }
        return if (reply.toByteArray(Charsets.UTF_8).size > MAX_REPLY_BYTES) failure("النتيجة أطول من المسموح") else reply
    }

    private fun failure(reason: String): String = buildJsonObject {
        put("ok", false)
        put("error", reason)
    }.toString()

    private companion object {
        /** HUMAIN refuses tool results over 15 KiB. */
        const val MAX_REPLY_BYTES = 15_000
    }
}
