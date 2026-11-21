package app.sanafi.assistant

import android.app.Application
import android.content.Intent
import app.sanafi.assistant.call.CallGate
import app.sanafi.assistant.call.CallService
import app.sanafi.assistant.ui.CallActivity

class SanafiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Both steps run from the visible screen that received the press: that is what lets the
        // service use the microphone and the call screen appear.
        CallGate.start = { context ->
            CallService.start(context)
            context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        CallGate.end = CallService::end
    }
}
