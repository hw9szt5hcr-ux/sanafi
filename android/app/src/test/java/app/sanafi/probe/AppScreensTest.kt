package app.sanafi.probe

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.sanafi.probe.core.ErrorHints
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.core.Status
import app.sanafi.probe.core.WakeMode
import app.sanafi.probe.data.ProbeStore
import app.sanafi.probe.data.Settings
import app.sanafi.probe.probes.AssistActivity
import app.sanafi.probe.probes.BootListenService
import app.sanafi.probe.probes.BootReceiver
import app.sanafi.probe.probes.CallProbe
import app.sanafi.probe.probes.MicMatrixProbe
import app.sanafi.probe.probes.MicWatchService
import app.sanafi.probe.probes.MicWatcher
import app.sanafi.probe.probes.TelephonyProbe
import app.sanafi.probe.probes.WakeListener
import app.sanafi.probe.probes.WakeService
import app.sanafi.probe.ui.Labels
import app.sanafi.probe.ui.MainActivity
import app.sanafi.probe.ui.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppScreensTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun views(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else listOf(root)

    private fun texts(root: View): List<String> = views(root).filterIsInstance<TextView>().map { it.text.toString() }

    private fun button(root: View, label: String): Button =
        views(root).filterIsInstance<Button>().first { it.text.toString() == label }

    @Test
    fun `first launch records the install and shows a row for every probe`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(Status.PASS, ProbeStore(app).get(ProbeId.INSTALL)!!.status)
        val shown = texts(activity.window.decorView)
        ProbeId.values().forEach { assertTrue("no row for $it", shown.contains(Labels.title(it))) }
        assertEquals(1, shown.count { it == Labels.status(Status.PASS) })
        assertEquals(ProbeId.values().size - 1, shown.count { it == Labels.status(Status.NOT_RUN) })
    }

    @Test
    fun `a failure saved while the app was away shows on its row with its detail`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ProbeStore(app).save(ProbeResult(ProbeId.MIC, Status.FAIL, "التسجيل صامت", mapOf("peak_dbfs" to -120.0), atEpochMs = 1))
        controller.pause().resume()
        val shown = texts(controller.get().window.decorView)
        assertTrue(shown.contains(Labels.status(Status.FAIL)))
        assertTrue(shown.any { it.contains("التسجيل صامت") && it.contains("peak_dbfs=-120.0") })
    }

    @Test
    fun `the report button shows the full report`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        button(activity.window.decorView, "اعرض التقرير").performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val report = texts(dialog.window!!.decorView).first { it.startsWith("SANAFI PROBE REPORT") }
        assertTrue(report.contains("INSTALL: PASS"))
        assertTrue(report.contains("TELEPHONY: NOT_RUN"))
    }

    @Test
    fun `settings refuse a link that is not https and keep nothing`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fields = views(activity.window.decorView).filterIsInstance<EditText>().filter { it !is CheckBox }
        fields[0].setText("http://example.com")
        fields[1].setText("s3cret")
        button(activity.window.decorView, "احفظ").performClick()
        assertEquals("", Settings(app).backendUrl)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `settings save an https link, the secret and the audio mode`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        val fields = views(root).filterIsInstance<EditText>().filter { it !is CheckBox }
        fields[0].setText(" https://sanafi.example.app/ ")
        fields[1].setText("s3cret")
        views(root).filterIsInstance<CheckBox>().first().isChecked = true
        button(root, "احفظ").performClick()
        val settings = Settings(app)
        assertEquals("https://sanafi.example.app/", settings.backendUrl)
        assertEquals("s3cret", settings.deviceSecret)
        assertTrue(settings.mediaAudio)
        assertTrue(settings.isConfigured())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the device id is created once and stays the same`() {
        val first = Settings(app).deviceId
        assertTrue(first.startsWith("p3pro-"))
        assertEquals(first, Settings(app).deviceId)
    }

    @Test
    fun `an assist intent is recorded as a button launch and opens the main screen`() {
        val intent = Intent(Intent.ACTION_ASSIST).setClass(app, AssistActivity::class.java)
        val activity = Robolectric.buildActivity(AssistActivity::class.java, intent).create().get()
        val saved = ProbeStore(app).get(ProbeId.ASSIST_BUTTON)!!
        assertEquals(Status.PASS, saved.status)
        assertTrue(saved.detail.contains("android.intent.action.ASSIST"))
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `boot completed starts the listening service marked as started from boot`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(app).nextStartedService
        assertEquals(BootListenService::class.java.name, started.component!!.className)
        assertTrue(started.getBooleanExtra(BootListenService.EXTRA_FROM_BOOT, false))
    }

    @Test
    fun `a call without settings fails with the settings hint and is saved`() = runBlocking {
        val statuses = mutableListOf<String>()
        val result = CallProbe(app).run("mul_abdullah", CallProbe.Mode.LATENCY) { statuses += it }
        assertEquals(ProbeId.CALL_LATENCY, result.id)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.detail.contains("الإعدادات"))
        assertTrue(statuses.isEmpty())
        assertEquals(result, ProbeStore(app).get(ProbeId.CALL_LATENCY))
        assertNull(ProbeStore(app).get(ProbeId.ECHO))
    }

    @Test
    fun `a configured call without microphone permission fails before any network use`() = runBlocking {
        Settings(app).apply { backendUrl = "https://unreachable.invalid"; deviceSecret = "s" }
        val statuses = mutableListOf<String>()
        val result = CallProbe(app).run(null, CallProbe.Mode.ECHO) { statuses += it }
        assertEquals(ProbeId.ECHO, result.id)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.detail.contains("الصلاحية"))
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `a call whose backend cannot be reached is saved as a network failure, not a crash`() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        Settings(app).apply { backendUrl = "https://sanafi-backend.invalid"; deviceSecret = "s" }
        val statuses = mutableListOf<String>()
        val result = CallProbe(app).run("mul_abdullah", CallProbe.Mode.LATENCY) { statuses += it }
        assertEquals(Status.FAIL, result.status)
        assertEquals(ErrorHints.forCode("network"), result.detail)
        assertEquals(listOf("يطلب مكالمة من الخادم…"), statuses)
        assertEquals(result, ProbeStore(app).get(ProbeId.CALL_LATENCY))
    }

    @Test
    fun `the wide microphone test without permission is saved as a permission failure`() = runBlocking {
        val statuses = mutableListOf<String>()
        val result = MicMatrixProbe(app).runAll { statuses += it }
        assertEquals(ProbeId.MIC, result.id)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.detail.contains("الصلاحية"))
        assertTrue(statuses.isEmpty())
        assertEquals(result, ProbeStore(app).get(ProbeId.MIC))
    }

    @Test
    fun `the main screen offers the wide microphone test`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(button(activity.window.decorView, "اختبار شامل (٣٠ ثانية)"))
    }

    @Test
    fun `telephony check on a box without phone hardware is saved as a failure`() {
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY, false)
        val result = TelephonyProbe(app).evaluate(rang = null)
        assertEquals(Status.FAIL, result.status)
        assertEquals(result, ProbeStore(app).get(ProbeId.TELEPHONY))
    }

    @Test
    fun `the microphone watch button starts the watching service`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        button(activity.window.decorView, "ابدأ المراقبة (٦٠ ثانية)").performClick()
        assertEquals(MicWatchService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test
    fun `a microphone watch without permission is saved as a permission failure`() {
        val result = MicWatcher(app).run(seconds = 1)
        assertEquals(ProbeId.MIC_WATCH, result.id)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.detail.contains("الصلاحية"))
        assertEquals(result, ProbeStore(app).get(ProbeId.MIC_WATCH))
    }

    @Test
    fun `the wake word row starts the listening service in the mode of the button pressed`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        button(activity.window.decorView, "اختبار النداء (٦٠ ثانية)").performClick()
        val calling = shadowOf(app).nextStartedService
        assertEquals(WakeService::class.java.name, calling.component!!.className)
        assertEquals(WakeMode.CALLING.name, calling.getStringExtra(WakeService.EXTRA_MODE))
        button(activity.window.decorView, "بدون نداء (٦٠ ثانية)").performClick()
        assertEquals(WakeMode.QUIET.name, shadowOf(app).nextStartedService.getStringExtra(WakeService.EXTRA_MODE))
    }

    @Test
    fun `a wake word run without permission is saved as a permission failure`() {
        val result = WakeListener(app).run(WakeMode.CALLING, seconds = 1)
        assertEquals(ProbeId.WAKE_OVER_MUSIC, result.id)
        assertEquals(Status.FAIL, result.status)
        assertTrue(result.detail.contains("الصلاحية"))
        assertEquals(result, ProbeStore(app).get(ProbeId.WAKE_OVER_MUSIC))
    }
}
