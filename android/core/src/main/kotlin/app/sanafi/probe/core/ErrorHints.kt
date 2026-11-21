package app.sanafi.probe.core

/** Plain Arabic explanation for each failure code the backend or the app can produce. */
object ErrorHints {
    fun forCode(code: String): String = when (code) {
        "AUTH_SCOPE_MISSING" -> "مفتاح HUMAIN ما عليه صلاحية agent. فعّلها من API keys في لوحة التحكم."
        "CREDITS_EXHAUSTED" -> "رصيد HUMAIN خلص. اشحن الرصيد وجرّب مرة ثانية."
        "CONCURRENCY_LIMIT_EXCEEDED" -> "في مكالمة ثانية شغالة على نفس الحساب. انتظر دقيقة وجرّب."
        "RATE_LIMIT_EXCEEDED" -> "وصلت حد الدقائق اليومي في خطتك. يتجدد منتصف الليل بتوقيت UTC."
        "bad_secret" -> "كلمة سر الجهاز غلط. تأكد منها في الإعدادات ومن قيمة DEVICE_SECRET في الخادم."
        "network" -> "ما قدر التطبيق يوصل للخادم. تأكد من النت ومن رابط الخادم في الإعدادات."
        else -> "صار خطأ غير متوقع ($code). انسخ التقرير وأرسله."
    }
}
