package app.sanafi.assistant.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.sanafi.assistant.call.CallService
import app.sanafi.assistant.call.CallUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The screen during a call: what Sanafi is doing, and one big button to hang up. */
class CallActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply {
            textSize = 36f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = label(CallUi.Connecting)
        }
        val detail = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        }
        val end = Button(this).apply {
            text = "إنهاء"
            textSize = 26f
            isAllCaps = false
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                CallService.end(this@CallActivity)
                if (CallService.ui.value is CallUi.Failed || CallService.ui.value == CallUi.Idle) finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(end, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        lifecycleScope.launch {
            var sawCall = false
            CallService.ui.collect { state ->
                if (state != CallUi.Idle) sawCall = true
                if (state == CallUi.Idle && sawCall) finish()
                status.text = label(state)
                detail.text = (state as? CallUi.Failed)?.message.orEmpty()
                end.text = if (state is CallUi.Failed) "إغلاق" else "إنهاء"
            }
        }
        // If no call ever shows up (the service could not start), do not leave an empty screen behind.
        lifecycleScope.launch {
            delay(NO_CALL_GRACE_MS)
            if (CallService.ui.value == CallUi.Idle) finish()
        }
    }

    private fun label(state: CallUi): String = when (state) {
        CallUi.Idle, CallUi.Connecting -> "يتصل…"
        CallUi.Listening -> "سنافي يسمعك"
        CallUi.Thinking -> "يفكر…"
        CallUi.Speaking -> "يتكلم"
        is CallUi.Failed -> "ما ضبطت"
    }

    private companion object {
        const val NO_CALL_GRACE_MS = 4_000L
    }
}
