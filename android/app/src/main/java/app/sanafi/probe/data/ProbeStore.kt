package app.sanafi.probe.data

import android.content.Context
import app.sanafi.probe.core.ProbeCodec
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.core.ProbeResults

/** Probe results kept on the device, one stored text per probe. */
class ProbeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("probe_results", Context.MODE_PRIVATE)

    /** Written synchronously: probes also run from services that may be killed right after. */
    fun save(result: ProbeResult) {
        prefs.edit().putString(result.id.name, ProbeCodec.encode(result)).commit()
    }

    fun get(id: ProbeId): ProbeResult? = prefs.getString(id.name, null)?.let(ProbeCodec::decode)

    fun all(): List<ProbeResult> = ProbeResults.complete(ProbeId.values().mapNotNull(::get))
}
