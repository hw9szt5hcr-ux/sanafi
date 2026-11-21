package app.sanafi.probe.probes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import app.sanafi.probe.core.ProbeReducer
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.data.ProbeStore

/** Question 8: can the box place a phone call from its own SIM? */
class TelephonyProbe(private val context: Context) {
    private fun callIntent(number: String) = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))

    /** [rang] is the owner's answer after a test call, or null before one was placed. */
    fun evaluate(rang: Boolean?): ProbeResult {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val result = ProbeReducer.telephony(
            hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            simReady = telephony?.simState == TelephonyManager.SIM_STATE_READY,
            canResolveCall = callIntent("123").resolveActivity(context.packageManager) != null,
            rang = rang,
            nowMs = System.currentTimeMillis(),
        )
        return result.also { ProbeStore(context).save(it) }
    }

    /** Opens the dialer on [number]; dials directly when the call permission was granted. */
    fun placeTestCall(activity: Activity, number: String): Boolean {
        val direct = activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = if (direct) callIntent(number) else Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return try {
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
