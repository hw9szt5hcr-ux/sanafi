package app.sanafi.assistant.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.sanafi.assistant.BuildConfig
import app.sanafi.assistant.call.CallGate
import app.sanafi.assistant.core.Memory
import app.sanafi.assistant.core.UsageLedger
import app.sanafi.assistant.data.DiagFile
import app.sanafi.assistant.data.MemoryFile
import app.sanafi.assistant.data.Prefs
import app.sanafi.assistant.data.UsageFile
import java.time.ZoneId

/** The owner's screen: setup, a try button, what Sanafi remembers, this month's minutes, and the report. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var usageLine: TextView
    private lateinit var memoryText: TextView
    private val voiceButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildScreen())
        askForPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val minutes = UsageFile(this).load().minutes(UsageLedger.monthKey(System.currentTimeMillis(), ZoneId.systemDefault()))
        val cost = "%.0f".format(java.util.Locale.US, minutes * RIYAL_PER_MINUTE)
        usageLine.text = "هذا الشهر: $minutes دقيقة، حوالي $cost ريال"
        memoryText.text = MemoryFile(this).load().render().ifEmpty { "ما في شي محفوظ للحين." }
        for ((label, button) in voiceButtons) button.alpha = if (label == prefs.voiceLabel) 1f else 0.45f
    }

    private fun buildScreen(): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        fun heading(text: String) = column.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        fun note(text: String = "") = TextView(this).apply { this.text = text; textSize = 17f }.also(column::addView)
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { column.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        column.addView(TextView(this).apply {
            text = "سنافي"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        note("اضغط زر الدركسون ضغطة مطوّلة وكلّمه. ضغطة ثانية تنهي المكالمة.")
        button("جرّب الحين") { tryNow() }

        heading("الإعداد")
        note("رابط الخادم (يبدأ بـ https://)")
        val url = EditText(this).apply {
            setText(prefs.backendUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            hint = "https://sanafi-backend.vercel.app"
        }
        column.addView(url)
        note("كلمة سر الجهاز")
        val secret = EditText(this).apply {
            setText(prefs.deviceSecret)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
        }
        column.addView(secret)
        button("احفظ") {
            val typedUrl = url.text.toString().trim()
            if (!typedUrl.startsWith("https://")) {
                toast("الرابط لازم يبدأ بـ https://")
            } else {
                prefs.backendUrl = typedUrl
                prefs.deviceSecret = secret.text.toString()
                toast("انحفظ")
            }
        }

        heading("الصوت")
        voiceButtons["mul_abdullah"] = button("عبدالله") { chooseVoice("mul_abdullah") }
        voiceButtons["mul_abdulaziz"] = button("عبدالعزيز") { chooseVoice("mul_abdulaziz") }

        heading("الدقائق")
        usageLine = note()

        heading("اللي يتذكره سنافي")
        memoryText = note()
        button("امسح الذاكرة") { confirmClearMemory() }

        heading("زر الدركسون")
        note("إذا تبي الزر يرجع يفتح Gemini، اضغط هنا ثم اضغط زر الدركسون واختر Google.")
        button("رجّع زر الدركسون لـ Gemini") {
            @Suppress("DEPRECATION")
            packageManager.clearPackagePreferredActivities(packageName)
            toast("تم. اضغط زر الدركسون واختر التطبيق اللي تبيه.")
        }

        heading("التقرير")
        note("إذا صار شي غلط، انسخ التقرير وأرسله.")
        button("انسخ التقرير") { copyReport() }
        note("معرّف الجهاز: ${prefs.deviceId}")
        return ScrollView(this).apply { addView(column) }
    }

    private fun tryNow() {
        if (prefs.isConfigured()) CallGate.start(this) else toast("اكتب رابط الخادم وكلمة السر واضغط «احفظ» أول.")
    }

    private fun chooseVoice(label: String) {
        prefs.voiceLabel = label
        refresh()
    }

    private fun confirmClearMemory() {
        AlertDialog.Builder(this)
            .setMessage("تمسح كل اللي يتذكره سنافي عنك؟")
            .setPositiveButton("امسح") { _, _ ->
                MemoryFile(this).save(Memory())
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyReport() {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / app ${BuildConfig.VERSION_NAME}"
        val report = DiagFile(this).load().render("SANAFI REPORT $device")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sanafi report", report))
        toast("انتسخ التقرير")
    }

    private fun askForPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), PERMISSIONS_REQUEST)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val RIYAL_PER_MINUTE = 0.5
        const val PERMISSIONS_REQUEST = 7
    }
}
