package app.sanafi.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.sanafi.assistant.call.CallGate
import app.sanafi.assistant.data.Prefs

/**
 * Receives the steering wheel button. A press with no call starts one; a press during a call
 * ends it. It shows nothing itself and closes at once.
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePress()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handlePress()
    }

    private fun handlePress() {
        when {
            CallGate.live -> CallGate.end(this)
            Prefs(this).isConfigured() -> CallGate.start(this)
            else -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
