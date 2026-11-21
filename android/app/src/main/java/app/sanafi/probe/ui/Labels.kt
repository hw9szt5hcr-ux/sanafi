package app.sanafi.probe.ui

import app.sanafi.probe.core.ProbeId
import app.sanafi.probe.core.Status

object Labels {
    fun title(id: ProbeId): String = when (id) {
        ProbeId.INSTALL -> "١. التثبيت"
        ProbeId.MIC -> "٢. الميكروفون"
        ProbeId.BOOT_LISTEN -> "٣. الاستماع بعد تشغيل السيارة"
        ProbeId.ASSIST_BUTTON -> "٤. زر الدركسون"
        ProbeId.CALL_LATENCY -> "٥. سرعة رد سنافي"
        ProbeId.ECHO -> "٦. الصدى"
        ProbeId.WAKE_OVER_MUSIC -> "٧. النداء فوق الأغنية"
        ProbeId.TELEPHONY -> "٨. الاتصال الهاتفي"
    }

    fun status(status: Status): String = when (status) {
        Status.NOT_RUN -> "ما انجرّب"
        Status.PASS -> "نجح"
        Status.FAIL -> "فشل"
    }

    fun color(status: Status): Int = when (status) {
        Status.NOT_RUN -> 0xFF8A8A8A.toInt()
        Status.PASS -> 0xFF1B8A3A.toInt()
        Status.FAIL -> 0xFFC62828.toInt()
    }
}
