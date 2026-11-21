package app.sanafi.probe.ui

import android.widget.Button
import android.widget.LinearLayout
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.data.ProbeStore

/** Wires each probe's buttons into its row on the main screen. */
class ProbeActions(
    private val activity: MainActivity,
    private val store: ProbeStore,
    private val refresh: () -> Unit,
) {
    fun attach(rows: Map<ProbeId, LinearLayout>) {
        // Each probe adds its buttons here as it is built.
    }

    private fun LinearLayout.button(label: String, onClick: () -> Unit) {
        addView(Button(activity).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }
}
