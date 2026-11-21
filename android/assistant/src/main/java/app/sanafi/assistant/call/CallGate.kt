package app.sanafi.assistant.call

import android.content.Context

/**
 * The one place that knows whether a call is live and how to start or end one. The steering
 * wheel button and the screens go through it; the call service fills it in.
 */
object CallGate {
    @Volatile var live: Boolean = false
    var start: (Context) -> Unit = {}
    var end: (Context) -> Unit = {}
}
