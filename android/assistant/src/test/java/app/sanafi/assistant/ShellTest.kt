package app.sanafi.assistant

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import app.sanafi.assistant.call.CallGate
import app.sanafi.assistant.core.DiagLog
import app.sanafi.assistant.core.Memory
import app.sanafi.assistant.core.UsageLedger
import app.sanafi.assistant.data.DiagFile
import app.sanafi.assistant.data.MemoryFile
import app.sanafi.assistant.data.Prefs
import app.sanafi.assistant.data.UsageFile
import app.sanafi.assistant.ui.MainActivity
import app.sanafi.assistant.ui.TriggerActivity
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShellTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var started = 0
    private var ended = 0

    @Before
    fun gate() {
        CallGate.live = false
        CallGate.start = { started++ }
        CallGate.end = { ended++ }
    }

    @After
    fun reset() {
        CallGate.live = false
        CallGate.start = {}
        CallGate.end = {}
    }

    private fun configure() = Prefs(app).apply { backendUrl = "https://x.app"; deviceSecret = "s" }

    private fun views(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else listOf(root)

    private fun texts(root: View): List<String> = views(root).filterIsInstance<TextView>().map { it.text.toString() }

    private fun button(root: View, label: String): Button = views(root).filterIsInstance<Button>().first { it.text.toString() == label }

    private fun main() = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `the four button intents resolve to the trigger screen`() {
        for (action in listOf("android.speech.action.WEB_SEARCH", Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST, "android.intent.action.VOICE_ASSIST")) {
            val handlers = app.packageManager.queryIntentActivities(Intent(action), 0).map { it.activityInfo.name }
            assertTrue("$action not handled: $handlers", handlers.contains(TriggerActivity::class.java.name))
        }
    }

    @Test
    fun `a button press starts a call when none is live and ends it when one is`() {
        configure()
        val press = Intent("android.speech.action.WEB_SEARCH")
        val first = Robolectric.buildActivity(TriggerActivity::class.java, press).create().get()
        assertEquals(1 to 0, started to ended)
        assertTrue(first.isFinishing)
        CallGate.live = true
        Robolectric.buildActivity(TriggerActivity::class.java, press).create()
        assertEquals(1 to 1, started to ended)
    }

    @Test
    fun `an unconfigured app opens the settings screen instead of calling`() {
        val activity = Robolectric.buildActivity(TriggerActivity::class.java, Intent("android.speech.action.WEB_SEARCH")).create().get()
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertEquals(0, started)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `settings refuse a link that is not https and save a valid one`() {
        val root = main().window.decorView
        val fields = views(root).filterIsInstance<EditText>()
        fields[0].setText("http://example.com")
        fields[1].setText("s3cret")
        button(root, "احفظ").performClick()
        assertFalse(Prefs(app).isConfigured())
        assertEquals("", Prefs(app).backendUrl)
        fields[0].setText(" https://sanafi.example.app/ ")
        button(root, "احفظ").performClick()
        assertEquals("https://sanafi.example.app/", Prefs(app).backendUrl)
        assertEquals("s3cret", Prefs(app).deviceSecret)
        assertTrue(Prefs(app).isConfigured())
    }

    @Test
    fun `the try button starts a call only when the app is configured`() {
        val unconfigured = main().window.decorView
        button(unconfigured, "جرّب الحين").performClick()
        assertEquals(0, started)
        configure()
        button(main().window.decorView, "جرّب الحين").performClick()
        assertEquals(1, started)
    }

    @Test
    fun `the device id is created once, voice ids are cached per voice, and the defaults are the measured ones`() {
        val prefs = Prefs(app)
        assertTrue(prefs.deviceId.startsWith("sanafi-"))
        assertEquals(prefs.deviceId, Prefs(app).deviceId)
        assertEquals("mul_abdullah", prefs.voiceLabel)
        assertTrue(prefs.mediaAudio)
        assertFalse(prefs.talkFirst)
        assertNull(prefs.lastServerUrl)
        prefs.cacheVoiceId("mul_abdullah", "v1")
        assertEquals("v1", Prefs(app).cachedVoiceId("mul_abdullah"))
        assertNull(Prefs(app).cachedVoiceId("mul_abdulaziz"))
        prefs.cacheVoiceId("mul_abdullah", null)
        assertNull(Prefs(app).cachedVoiceId("mul_abdullah"))
    }

    @Test
    fun `choosing the other voice is saved`() {
        button(main().window.decorView, "عبدالعزيز").performClick()
        assertEquals("mul_abdulaziz", Prefs(app).voiceLabel)
    }

    @Test
    fun `memory, usage and the report survive a restart of the app`() {
        MemoryFile(app).save(Memory(listOf("اسمه عبدالله")))
        UsageFile(app).save(UsageLedger(mapOf("2026-11" to 7)))
        DiagFile(app).save(DiagLog().apply { add(5, "end", "user") })
        assertEquals(listOf("اسمه عبدالله"), MemoryFile(app).load().facts)
        assertEquals(7, UsageFile(app).load().minutes("2026-11"))
        assertEquals("H\n5 end user", DiagFile(app).load().render("H"))
    }

    @Test
    fun `clearing the memory asks first and then empties the file`() {
        MemoryFile(app).save(Memory(listOf("اسمه عبدالله")))
        val root = main().window.decorView
        assertTrue(texts(root).any { it.contains("اسمه عبدالله") })
        button(root, "امسح الذاكرة").performClick()
        assertEquals(1, MemoryFile(app).load().facts.size)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(MemoryFile(app).load().facts.isEmpty())
    }

    @Test
    fun `the month line shows minutes and their cost`() {
        val month = UsageLedger.monthKey(System.currentTimeMillis(), ZoneId.systemDefault())
        UsageFile(app).save(UsageLedger(mapOf(month to 12)))
        assertTrue(texts(main().window.decorView).any { it.contains("12 دقيقة") && it.contains("6 ريال") })
    }

    @Test
    fun `the report button copies the report with its header`() {
        DiagFile(app).save(DiagLog().apply { add(5, "end", "user") })
        button(main().window.decorView, "انسخ التقرير").performClick()
        val clip = app.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip.startsWith("SANAFI REPORT"))
        assertTrue(clip.contains("5 end user"))
    }
}
