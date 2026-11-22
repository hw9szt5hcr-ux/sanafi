package app.sanafi.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.sanafi.assistant.core.After
import app.sanafi.assistant.core.Match
import app.sanafi.assistant.core.NameMatcher
import app.sanafi.assistant.core.ToolArgs
import app.sanafi.assistant.core.ToolHandler
import app.sanafi.assistant.core.ToolOutcome
import app.sanafi.assistant.data.MemoryFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Volume and the play, pause and skip keys of whatever is playing. */
interface MediaPort {
    fun volume(up: Boolean)
    fun key(keyCode: Int, delayMs: Long)
}

class SystemMedia(context: Context) : MediaPort {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun volume(up: Boolean) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } catch (_: Exception) {}
    }

    override fun key(keyCode: Int, delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {}
        }, delayMs)
    }
}

/**
 * What Sanafi can do on the box when the agent asks: each entry answers one client tool.
 *
 * Commands that only show something open at once. Commands that need the microphone or the
 * music channel run through `thenAct`, the moment the call has closed.
 */
class DeviceTools(context: Context, private val media: MediaPort = SystemMedia(context)) {
    private val context = context.applicationContext
    private val memoryFile = MemoryFile(context)
    private val catalog = AppCatalog(context)
    private val contacts = Contacts(context)

    fun handlers(): Map<String, ToolHandler> = mapOf(
        "play_music" to ToolHandler(::playMusic),
        "navigate" to ToolHandler(::navigate),
        "call_contact" to ToolHandler(::callContact),
        "open_app" to ToolHandler(::openApp),
        "media_control" to ToolHandler(::mediaControl),
        "remember" to ToolHandler(::remember),
        "forget" to ToolHandler(::forget),
    )

    /** The app names told to the agent so it can ask for one by its real name. */
    fun appLabels(): List<String> = catalog.apps().map { it.label }

    private fun done(thenAct: (() -> Unit)? = null, say: String = "أبشر") = ToolOutcome.Done(say = say, after = After.END_CALL, thenAct = thenAct)
    private fun resolves(intent: Intent): Boolean = try {
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (_: Exception) {
        false
    }
    private fun open(intent: Intent) = context.startActivity(intent)
    private fun candidates(names: List<String>) =
        ToolOutcome.Done(data = buildJsonObject { put("candidates", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) })

    private fun playMusic(args: JsonObject): ToolOutcome {
        val query = ToolArgs.string(args, "query") ?: return ToolOutcome.Failed("ناقصني اسم الأغنية")
        val intent = listOf(Intents.playMusic(query), Intents.playMusicFallback(query)).firstOrNull(::resolves)
            ?: return ToolOutcome.Failed("يوتيوب ميوزك مو مثبت على الجهاز")
        return done(thenAct = { open(intent) })
    }

    private fun navigate(args: JsonObject): ToolOutcome {
        val place = ToolArgs.string(args, "place") ?: return ToolOutcome.Failed("ناقصني اسم المكان")
        val intent = listOf(Intents.navigate(place), Intents.navigateFallback(place)).firstOrNull(::resolves)
            ?: return ToolOutcome.Failed("ما في تطبيق خرائط على الجهاز")
        open(intent)
        return done()
    }

    private fun openApp(args: JsonObject): ToolOutcome {
        val wanted = ToolArgs.string(args, "app") ?: return ToolOutcome.Failed("ناقصني اسم التطبيق")
        val apps = catalog.apps()
        return when (val match = NameMatcher.match(wanted, apps.map { it.label })) {
            is Match.One -> {
                open(apps[match.index].launch)
                done()
            }
            is Match.Several -> candidates(match.indices.map { apps[it].label })
            Match.None -> ToolOutcome.Failed("ما لقيت تطبيق بهالاسم")
        }
    }

    private fun callContact(args: JsonObject): ToolOutcome {
        val name = ToolArgs.string(args, "name") ?: return ToolOutcome.Failed("ناقصني الاسم أو الرقم")
        phoneNumber(name)?.let { number -> return dial(number, "أبشر") }
        if (!contacts.hasPermission()) return ToolOutcome.Failed("ما عندي صلاحية جهات الاتصال. فعّلها من شاشة تطبيق سنافي")
        val entries = contacts.entries()
        return when (val match = NameMatcher.match(name, entries.map { it.name })) {
            is Match.One -> entries[match.index].let { dial(it.number, "أبشر، أدق على ${it.name}") }
            is Match.Several -> candidates(match.indices.map { entries[it].name })
            Match.None -> ToolOutcome.Failed("ما لقيت $name في جهات الاتصال")
        }
    }

    /** What was said is a phone number when it is only digits and separators, with at least five digits. */
    private fun phoneNumber(spoken: String): String? {
        val latin = spoken.map { ch -> if (ch in '٠'..'٩') '0' + (ch - '٠') else ch }.joinToString("")
        if (latin.any { !(it.isDigit() || it in " +-()") }) return null
        val number = latin.filter { it.isDigit() || it == '+' }
        return number.takeIf { it.count(Char::isDigit) >= 5 }
    }

    private fun dial(number: String, say: String): ToolOutcome {
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = listOfNotNull(Intents.call(number).takeIf { canCall }, Intents.dial(number)).firstOrNull(::resolves)
            ?: return ToolOutcome.Failed("ما في تطبيق اتصال على الجهاز")
        return done(thenAct = { open(intent) }, say = say)
    }

    private fun mediaControl(args: JsonObject): ToolOutcome {
        val key = when (ToolArgs.string(args, "action")) {
            "volume_up" -> return done().also { media.volume(up = true) }
            "volume_down" -> return done().also { media.volume(up = false) }
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolOutcome.Failed("ما فهمت الأمر")
        }
        // The player gets the sound back when the call closes; the key is sent just after that.
        return done(thenAct = { media.key(key, KEY_DELAY_MS) })
    }

    private fun remember(args: JsonObject): ToolOutcome {
        val fact = ToolArgs.string(args, "fact") ?: return ToolOutcome.Failed("ناقصني المعلومة")
        val memory = memoryFile.load()
        if (!memory.remember(fact)) return ToolOutcome.Done(say = "أعرفها من قبل")
        memoryFile.save(memory)
        return ToolOutcome.Done(say = "حفظتها")
    }

    private fun forget(args: JsonObject): ToolOutcome {
        val words = ToolArgs.string(args, "fact") ?: return ToolOutcome.Failed("ناقصني المعلومة")
        val memory = memoryFile.load()
        if (memory.forget(words) == 0) return ToolOutcome.Done(say = "ما لقيت شي بهالمعنى")
        memoryFile.save(memory)
        return ToolOutcome.Done(say = "نسيتها")
    }

    private companion object {
        const val KEY_DELAY_MS = 400L
    }
}
