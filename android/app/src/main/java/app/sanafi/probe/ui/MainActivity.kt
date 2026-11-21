package app.sanafi.probe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.sanafi.probe.BuildConfig
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.ProbeResult
import app.sanafi.probe.core.ReportFormatter
import app.sanafi.probe.core.Status
import app.sanafi.probe.data.ProbeStore

/** The results screen: one row per Phase 0 question, with its run button and outcome. */
class MainActivity : AppCompatActivity() {
    private lateinit var store: ProbeStore
    private val statusViews = mutableMapOf<ProbeId, TextView>()
    private val detailViews = mutableMapOf<ProbeId, TextView>()
    private val actionRows = mutableMapOf<ProbeId, LinearLayout>()
    private lateinit var actions: ProbeActions
    private var afterPermission: ((Boolean) -> Unit)? = null
    private var awaitingRangAnswer = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        afterPermission?.invoke(granted)
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProbeStore(this)
        if (store.get(ProbeId.INSTALL) == null) {
            store.save(ProbeResult(ProbeId.INSTALL, Status.PASS, "التطبيق تثبّت واشتغل.", atEpochMs = System.currentTimeMillis()))
        }
        setContentView(buildScreen())
        actions = ProbeActions(this, ::refresh).also { it.attach(actionRows) }
    }

    /** Runs [then] once [permission] is granted; with [proceedWhenDenied] it runs either way. */
    fun withPermission(permission: String, proceedWhenDenied: Boolean = false, then: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return then()
        afterPermission = { granted ->
            if (granted || proceedWhenDenied) then()
            else Toast.makeText(this, "الاختبار يحتاج هالصلاحية. اسمح بها وجرّب مرة ثانية.", Toast.LENGTH_LONG).show()
        }
        permissionLauncher.launch(permission)
    }

    /** Shows a running status in a probe's row until its result is saved. */
    fun showProgress(id: ProbeId, text: String) {
        detailViews[id]?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    /** The owner is leaving for the dialer; ask whether the phone rang when they come back. */
    fun expectRangAnswer() {
        awaitingRangAnswer = true
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // A launch through the assistant path keeps listening for a few seconds before it saves.
        window.decorView.postDelayed({ if (!isFinishing) refresh() }, 4500)
        if (awaitingRangAnswer) {
            awaitingRangAnswer = false
            actions.askIfItRang()
        }
    }

    fun refresh() {
        for (result in store.all()) {
            statusViews[result.id]?.apply {
                text = Labels.status(result.status)
                setTextColor(Labels.color(result.status))
            }
            detailViews[result.id]?.apply {
                val numbers = result.numbers.entries.joinToString("  ") { "${it.key}=${"%.1f".format(java.util.Locale.US, it.value)}" }
                text = listOf(result.detail, numbers).filter { it.isNotBlank() }.joinToString("\n")
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun buildScreen(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "اختبار سنافي — المرحلة صفر"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "شغّل الاختبارات بالترتيب، وفي الآخر اضغط «اعرض التقرير» وصوّر الشاشة."
            textSize = 16f
            setPadding(0, pad / 2, 0, pad)
        })
        val topButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topButtons.addView(Button(this).apply {
            text = "الإعدادات"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        topButtons.addView(Button(this).apply {
            text = "اعرض التقرير"
            setOnClickListener { showReport() }
        })
        column.addView(topButtons)

        for (id in ProbeId.values()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad / 2)
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            header.addView(TextView(this).apply {
                text = Labels.title(id)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this).apply { textSize = 17f }.also { statusViews[id] = it })
            card.addView(header)
            card.addView(TextView(this).apply { textSize = 15f }.also { detailViews[id] = it })
            card.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { actionRows[id] = it })
            column.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / app ${BuildConfig.VERSION_NAME}"

    private fun showReport() {
        val report = ReportFormatter.format(store.all(), deviceLine())
        val view = TextView(this).apply {
            text = report
            textSize = 13f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("انسخ") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sanafi report", report))
                Toast.makeText(this, "انتسخ التقرير", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("شارك") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "شارك التقرير"))
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }
}
