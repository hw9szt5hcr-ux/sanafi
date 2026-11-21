package app.sanafi.probe.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.WakeMode
import app.sanafi.probe.probes.BootListenService
import app.sanafi.probe.probes.CallProbe
import app.sanafi.probe.probes.MicMatrixProbe
import app.sanafi.probe.probes.MicProbe
import app.sanafi.probe.probes.MicRecorder
import app.sanafi.probe.probes.MicWatchService
import app.sanafi.probe.probes.MicWatcher
import app.sanafi.probe.probes.TelephonyProbe
import app.sanafi.probe.probes.WakeListener
import app.sanafi.probe.probes.WakeService
import kotlinx.coroutines.launch

/** Wires each probe's buttons into its row on the main screen. */
class ProbeActions(private val activity: MainActivity, private val refresh: () -> Unit) {
    private val mic = MicProbe(activity)
    private val micMatrix = MicMatrixProbe(activity)
    private val call = CallProbe(activity)
    private val telephony = TelephonyProbe(activity)
    private var callRunning = false

    fun attach(rows: Map<ProbeId, LinearLayout>) {
        rows.getValue(ProbeId.MIC).apply {
            button("سجّل ٥ ثواني") {
                activity.withPermission(Manifest.permission.RECORD_AUDIO) {
                    activity.showProgress(ProbeId.MIC, "يسجّل… تكلّم الحين")
                    activity.lifecycleScope.launch { mic.run(); refresh() }
                }
            }
            button("اختبار شامل (٣٠ ثانية)") {
                activity.withPermission(Manifest.permission.RECORD_AUDIO) {
                    activity.lifecycleScope.launch {
                        micMatrix.runAll { status -> activity.showProgress(ProbeId.MIC, status) }
                        refresh()
                    }
                }
            }
            button("اسمع التسجيل") {
                if (mic.lastRecording().isEmpty()) toast("سجّل أول") else MicRecorder.play(mic.lastRecording())
            }
        }

        rows.getValue(ProbeId.BOOT_LISTEN).apply {
            note("أعد تشغيل الجهاز (أو السيارة) ثم افتح التطبيق: النتيجة تنكتب بروحها.")
            button("تجربة يدوية") {
                activity.withPermission(Manifest.permission.RECORD_AUDIO) {
                    activity.startForegroundService(Intent(activity, BootListenService::class.java))
                    activity.showProgress(ProbeId.BOOT_LISTEN, "يسجّل ٣ ثواني…")
                    postDelayed({ refresh() }, 4500)
                }
            }
        }

        rows.getValue(ProbeId.ASSIST_BUTTON).apply {
            note("اضغط زر الدركسون ضغطة مطوّلة. إذا طلعت قائمة «فتح باستخدام» اختر «اختبار سنافي» ثم «دائماً».")
            button("اختيار المساعد") { openAssistantSettings() }
        }

        rows.getValue(ProbeId.MIC_WATCH).apply {
            note("اضغط «ابدأ المراقبة»، ارجع للشاشة الرئيسية، اضغط زر الدركسون مطوّل وتكلم ١٠ ثواني. بعد دقيقة ارجع هنا.")
            button("ابدأ المراقبة (٦٠ ثانية)") {
                activity.withPermission(Manifest.permission.RECORD_AUDIO) {
                    activity.startForegroundService(Intent(activity, MicWatchService::class.java))
                    activity.showProgress(ProbeId.MIC_WATCH, "يراقب الميكروفون دقيقة… روح اضغط زر الدركسون وتكلم.")
                    postDelayed({ refresh() }, (MicWatcher.WATCH_SECONDS + 3) * 1000L)
                }
            }
        }

        rows.getValue(ProbeId.CALL_LATENCY).apply {
            button("اتصال بصوت عبدالله") { startCall("mul_abdullah", CallProbe.Mode.LATENCY) }
            button("اتصال بصوت عبدالعزيز") { startCall("mul_abdulaziz", CallProbe.Mode.LATENCY) }
        }

        rows.getValue(ProbeId.ECHO).apply {
            note("مكالمة تسكت فيها تماماً ٤٠ ثانية.")
            button("اختبار الصدى") { startCall("mul_abdullah", CallProbe.Mode.ECHO) }
        }

        rows.getValue(ProbeId.WAKE_OVER_MUSIC).apply {
            note("في كل اختبار نداء قل «أليكسا» ٥ مرات وبين كل مرة ٥ ثواني؛ تسمع صفارة كل ما يلقطها. الأغنية تكون شغالة بصوتها المعتاد، إلا في «نداء بدون أغنية». في «بدون نداء» خل الأغنية شغالة ولا تتكلم.")
            button("اختبار النداء (٦٠ ثانية)") { startWake(WakeMode.CALLING) }
            button("نداء مع عزل الصدى (٦٠ ثانية)") { startWake(WakeMode.FILTERED) }
            button("بدون نداء (٦٠ ثانية)") { startWake(WakeMode.QUIET) }
            button("نداء بدون أغنية (٦٠ ثانية)") { startWake(WakeMode.NO_MUSIC) }
        }

        rows.getValue(ProbeId.TELEPHONY).apply {
            button("افحص الجهاز") { telephony.evaluate(rang = null); refresh() }
            button("اتصل برقم") { askNumberAndCall() }
        }
    }

    /** Called when the owner returns from the dialer after a test call. */
    fun askIfItRang() {
        AlertDialog.Builder(activity)
            .setMessage("رنّ الجوال اللي اتصلت عليه؟")
            .setPositiveButton("نعم") { _, _ -> telephony.evaluate(rang = true); refresh() }
            .setNegativeButton("لا") { _, _ -> telephony.evaluate(rang = false); refresh() }
            .setCancelable(false)
            .show()
    }

    private fun startWake(mode: WakeMode) {
        activity.withPermission(Manifest.permission.RECORD_AUDIO) {
            activity.startForegroundService(Intent(activity, WakeService::class.java).putExtra(WakeService.EXTRA_MODE, mode.name))
            val text = if (mode == WakeMode.QUIET) "يسمع دقيقة… لا تتكلم." else "يسمع دقيقة… قل «أليكسا» ٥ مرات."
            activity.showProgress(ProbeId.WAKE_OVER_MUSIC, text)
            activity.window.decorView.postDelayed({ refresh() }, (WakeListener.LISTEN_SECONDS + 3) * 1000L)
        }
    }

    private fun startCall(voiceLabel: String, mode: CallProbe.Mode) {
        if (callRunning) return toast("في مكالمة شغالة")
        activity.withPermission(Manifest.permission.RECORD_AUDIO) {
            AlertDialog.Builder(activity)
                .setMessage("هالاختبار مكالمة حقيقية تنحسب دقيقة من رصيد HUMAIN (حوالي نص ريال). نبدأ؟")
                .setPositiveButton("ابدأ") { _, _ ->
                    callRunning = true
                    activity.lifecycleScope.launch {
                        try {
                            call.run(voiceLabel, mode) { status -> activity.showProgress(mode.probe, status) }
                        } finally {
                            callRunning = false
                            refresh()
                        }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun askNumberAndCall() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "رقم جوالك"
            textDirection = View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(activity)
            .setMessage("اكتب رقم جوالك عشان نجرّب مكالمة من شريحة الجهاز.")
            .setView(input)
            .setPositiveButton("اتصل") { _, _ ->
                val number = input.text.toString().trim()
                if (number.length < 5) return@setPositiveButton toast("الرقم قصير")
                activity.withPermission(Manifest.permission.CALL_PHONE, proceedWhenDenied = true) {
                    if (telephony.placeTestCall(activity, number)) activity.expectRangAnswer() else {
                        toast("ما قدر التطبيق يفتح الاتصال")
                        telephony.evaluate(rang = null)
                        refresh()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openAssistantSettings() {
        val candidates = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in candidates) {
            try {
                activity.startActivity(Intent(action))
                return
            } catch (_: Exception) {
                // Try the next settings screen.
            }
        }
        toast("افتح إعدادات الجهاز يدوياً: التطبيقات ← التطبيقات الافتراضية ← المساعد الرقمي")
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun LinearLayout.button(label: String, onClick: () -> Unit) {
        addView(Button(activity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun LinearLayout.note(text: String) {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            this.text = text
            textSize = 14f
        })
    }
}
