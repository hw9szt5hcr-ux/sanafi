package app.sanafi.assistant

import android.Manifest
import android.app.Application
import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import app.sanafi.assistant.core.After
import app.sanafi.assistant.core.ToolOutcome
import app.sanafi.assistant.tools.AppCatalog
import app.sanafi.assistant.tools.DeviceTools
import app.sanafi.assistant.tools.Intents
import app.sanafi.assistant.tools.MediaPort
import java.net.URLEncoder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceToolsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val media = object : MediaPort {
        val calls = mutableListOf<String>()
        override fun volume(up: Boolean) { calls += if (up) "volume_up" else "volume_down" }
        override fun key(keyCode: Int, delayMs: Long) { calls += "key:$keyCode after ${delayMs}ms" }
    }
    private val tools get() = DeviceTools(app, media)

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
    private suspend fun run(tool: String, vararg pairs: Pair<String, String>): ToolOutcome = tools.handlers().getValue(tool).run(args(*pairs))
    private fun started(): Intent? = shadowOf(app).nextStartedActivity
    private fun resolvable(intent: Intent) = shadowOf(app.packageManager).addResolveInfoForIntent(intent, info("pkg", "Handler", "Handler"))
    private fun info(pkg: String, cls: String, label: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = pkg; name = cls }
        nonLocalizedLabel = label
    }
    private fun installApps(vararg labels: String) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, labels.mapIndexed { i, label -> info("pkg$i", "Main$i", label) })
    }

    class FakeContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)).apply {
                rows.forEach { addRow(it) }
            }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
        companion object { var rows: List<Array<Any>> = emptyList() }
    }

    private fun contacts(vararg rows: Triple<Long, String, String>) {
        FakeContacts.rows = rows.map { arrayOf<Any>(it.first, it.second, it.third) }
        Robolectric.buildContentProvider(FakeContacts::class.java).create(ContactsContract.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @Test
    fun `the music intent targets YouTube Music with the query as a play-from-search request`() {
        val intent = Intents.playMusic("الأماكن محمد عبده")
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals("com.google.android.apps.youtube.music", intent.`package`)
        assertEquals("الأماكن محمد عبده", intent.getStringExtra(SearchManager.QUERY))
        assertEquals("vnd.android.cursor.item/*", intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("https://music.youtube.com/search?q=" + URLEncoder.encode("الأماكن", "UTF-8"), Intents.playMusicFallback("الأماكن").dataString)
    }

    @Test
    fun `navigation and its fallback encode an Arabic place name`() {
        val place = "قهوة الفلانية"
        val encoded = URLEncoder.encode(place, "UTF-8")
        assertEquals("google.navigation:q=$encoded", Intents.navigate(place).dataString)
        assertEquals("com.google.android.apps.maps", Intents.navigate(place).`package`)
        assertEquals("geo:0,0?q=$encoded", Intents.navigateFallback(place).dataString)
        assertNull(Intents.navigateFallback(place).`package`)
    }

    @Test
    fun `play_music says one word, ends the call, and only opens YouTube Music afterwards`() = runTest {
        resolvable(Intents.playMusic("فيروز"))
        val outcome = run("play_music", "query" to "فيروز") as ToolOutcome.Done
        assertEquals("أبشر", outcome.say)
        assertEquals(After.END_CALL, outcome.after)
        assertNull(started())
        outcome.thenAct!!.invoke()
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, started()!!.action)
    }

    @Test
    fun `play_music uses the search fallback when play-from-search is not offered, and fails when the app is missing`() = runTest {
        assertTrue(run("play_music", "query" to "فيروز") is ToolOutcome.Failed)
        resolvable(Intents.playMusicFallback("فيروز"))
        val outcome = run("play_music", "query" to "فيروز") as ToolOutcome.Done
        outcome.thenAct!!.invoke()
        assertEquals(Intent.ACTION_VIEW, started()!!.action)
    }

    @Test
    fun `navigate opens Maps at the moment the tool is called`() = runTest {
        resolvable(Intents.navigate("مطار حمد"))
        val outcome = run("navigate", "place" to "مطار حمد") as ToolOutcome.Done
        assertEquals(Intents.navigate("مطار حمد").dataString, started()!!.dataString)
        assertNull(outcome.thenAct)
        assertEquals(After.END_CALL, outcome.after)
        assertEquals("أبشر", outcome.say)
    }

    @Test
    fun `navigate falls back to any map app and fails when there is none`() = runTest {
        assertTrue(run("navigate", "place" to "مطار حمد") is ToolOutcome.Failed)
        assertNull(started())
        resolvable(Intents.navigateFallback("مطار حمد"))
        assertTrue(run("navigate", "place" to "مطار حمد") is ToolOutcome.Done)
        assertEquals(Intents.navigateFallback("مطار حمد").dataString, started()!!.dataString)
    }

    @Test
    fun `open_app launches the one match at once, hands back several, and fails on none`() = runTest {
        installApps("YouTube", "YouTube Music", "Maps")
        val maps = run("open_app", "app" to "maps") as ToolOutcome.Done
        assertEquals("pkg2", started()!!.component!!.packageName)
        assertNull(maps.thenAct)
        assertEquals(After.END_CALL, maps.after)
        run("open_app", "app" to "youtube")
        assertEquals("pkg0", started()!!.component!!.packageName)
        val several = run("open_app", "app" to "you") as ToolOutcome.Done
        assertEquals(listOf("YouTube", "YouTube Music"), several.data!!.getValue("candidates").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(After.KEEP_TALKING, several.after)
        assertNull(started())
        assertTrue(run("open_app", "app" to "تيك توك") is ToolOutcome.Failed)
    }

    @Test
    fun `call_contact finds a contact written in another spelling and calls its number after the call`() = runTest {
        contacts(Triple(1L, "أحمد العتيبي", "0555"), Triple(2L, "سعد", "0666"))
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        resolvable(Intents.call("0555"))
        val outcome = run("call_contact", "name" to "احمد") as ToolOutcome.Done
        assertEquals(After.END_CALL, outcome.after)
        assertTrue(outcome.say!!.contains("أحمد العتيبي"))
        assertNull(started())
        outcome.thenAct!!.invoke()
        val call = started()!!
        assertEquals(Intent.ACTION_CALL, call.action)
        assertEquals("tel:0555", call.dataString)
    }

    @Test
    fun `two contacts with the same first name are handed back as candidates, and one contact with two numbers is one entry`() = runTest {
        contacts(Triple(1L, "خالد المري", "0111"), Triple(1L, "خالد المري", "0112"), Triple(2L, "خالد الدوسري", "0222"))
        val outcome = run("call_contact", "name" to "خالد") as ToolOutcome.Done
        assertEquals(listOf("خالد المري", "خالد الدوسري"), outcome.data!!.getValue("candidates").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(After.KEEP_TALKING, outcome.after)
        assertNull(outcome.thenAct)
        assertTrue(run("call_contact", "name" to "فهد") is ToolOutcome.Failed)
    }

    @Test
    fun `a spoken phone number is called directly, in Latin or Arabic digits`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        resolvable(Intents.call("0551234567"))
        (run("call_contact", "name" to "055 123 4567") as ToolOutcome.Done).thenAct!!.invoke()
        assertEquals("tel:0551234567", started()!!.dataString)
        (run("call_contact", "name" to "٠٥٥١٢٣٤٥٦٧") as ToolOutcome.Done).thenAct!!.invoke()
        assertEquals("tel:0551234567", started()!!.dataString)
    }

    @Test
    fun `without the contacts permission the tool says so, and without the call permission it opens the dialer`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        val refused = run("call_contact", "name" to "أحمد") as ToolOutcome.Failed
        assertTrue(refused.reason.contains("صلاحية"))
        resolvable(Intents.dial("0551234567"))
        (run("call_contact", "name" to "0551234567") as ToolOutcome.Done).thenAct!!.invoke()
        assertEquals(Intent.ACTION_DIAL, started()!!.action)
        assertTrue(run("call_contact", "name" to "0559999999") is ToolOutcome.Failed)
    }

    @Test
    fun `volume changes happen at once and transport keys are sent after the call`() = runTest {
        val up = run("media_control", "action" to "volume_up") as ToolOutcome.Done
        assertEquals(listOf("volume_up"), media.calls)
        assertNull(up.thenAct)
        assertEquals(After.END_CALL, up.after)
        run("media_control", "action" to "volume_down")
        val pause = run("media_control", "action" to "pause") as ToolOutcome.Done
        assertEquals(listOf("volume_up", "volume_down"), media.calls)
        pause.thenAct!!.invoke()
        assertEquals("key:${KeyEvent.KEYCODE_MEDIA_PAUSE} after 400ms", media.calls.last())
        val keys = mapOf("play" to KeyEvent.KEYCODE_MEDIA_PLAY, "next" to KeyEvent.KEYCODE_MEDIA_NEXT, "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        for ((action, code) in keys) {
            (run("media_control", "action" to action) as ToolOutcome.Done).thenAct!!.invoke()
            assertEquals("key:$code after 400ms", media.calls.last())
        }
    }

    @Test
    fun `a blank argument and an unknown media action fail with a sentence, never an exception`() = runTest {
        for ((tool, argument) in listOf("play_music" to "query", "navigate" to "place", "call_contact" to "name", "open_app" to "app", "media_control" to "action")) {
            assertTrue(tool, run(tool) is ToolOutcome.Failed)
            assertTrue(tool, run(tool, argument to "   ") is ToolOutcome.Failed)
        }
        assertTrue(run("media_control", "action" to "explode") is ToolOutcome.Failed)
        assertTrue(media.calls.isEmpty())
        assertNull(started())
    }

    @Test
    fun `the app list sent to the agent has at most 80 labels of at most 40 characters and leaves this app out`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val many = (1..90).map { info("pkg$it", "Main", "App %03d".format(it)) } + info(app.packageName, "Main", "سنافي") + info("long", "Main", "x".repeat(60))
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, many)
        val labels = tools.appLabels()
        assertEquals(80, labels.size)
        assertTrue(labels.none { it == "سنافي" })
        assertTrue(labels.all { it.length <= 40 })
        assertEquals(labels, labels.sorted())
        assertEquals(labels, AppCatalog(app).apps().map { it.label })
        assertEquals(7, tools.handlers().size)
    }
}
