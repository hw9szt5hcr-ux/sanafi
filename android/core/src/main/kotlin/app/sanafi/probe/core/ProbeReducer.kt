package app.sanafi.probe.core

/** One microphone attempt: which source or input was tried and how loud the loudest sample was. */
data class MicReading(val label: String, val peakDbfs: Double)

/**
 * A long microphone watch: the loudest sample of each window, and what Android reported meanwhile.
 * [silencedWindows] counts windows where Android said it was muting this recording;
 * [otherRecorderWindows] counts windows where some other app was recording too.
 */
data class MicWatchLog(
    val windowMs: Int,
    val windowPeaksDbfs: List<Double>,
    val silencedWindows: Int,
    val otherRecorderWindows: Int,
    val sanafiIsAssistant: Boolean,
)

/** Turns raw observations from a probe into the result that is stored and reported. */
object ProbeReducer {
    private const val MAX_SELF_INTERRUPTIONS = 1

    fun mic(permissionGranted: Boolean, pcm: ShortArray, nowMs: Long): ProbeResult {
        if (!permissionGranted) {
            return ProbeResult(ProbeId.MIC, Status.FAIL, "ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.", atEpochMs = nowMs)
        }
        val peak = AudioLevel.peakDbfs(pcm)
        if (AudioLevel.isSilent(pcm)) {
            return ProbeResult(
                ProbeId.MIC, Status.FAIL, "التسجيل صامت: الميكروفون ما يوصّل صوت للتطبيق.",
                mapOf("peak_dbfs" to peak.coerceAtLeast(-120.0)), nowMs,
            )
        }
        return ProbeResult(ProbeId.MIC, Status.PASS, "الميكروفون يلقط الصوت.", mapOf("peak_dbfs" to peak), nowMs)
    }

    fun call(timeline: LatencyTimeline, errorCode: String?, nowMs: Long): ProbeResult {
        if (errorCode != null) {
            return ProbeResult(ProbeId.CALL_LATENCY, Status.FAIL, ErrorHints.forCode(errorCode), atEpochMs = nowMs)
        }
        val numbers = timeline.deltas().mapValues { it.value.toDouble() }
        val total = timeline.totalToFirstSpeechMs()
            ?: return ProbeResult(
                ProbeId.CALL_LATENCY, Status.FAIL,
                "المساعد ما تكلم. آخر خطوة وصلت لها المكالمة: ${timeline.lastMark()?.name ?: "NONE"}.",
                numbers, nowMs,
            )
        return ProbeResult(
            ProbeId.CALL_LATENCY, Status.PASS,
            "أول رد بعد $total مللي ثانية من الضغط.",
            numbers + ("total_ms" to total.toDouble()), nowMs,
        )
    }

    fun echo(selfInterruptions: Int, agentTurns: Int, nowMs: Long): ProbeResult {
        val numbers = mapOf("self_interruptions" to selfInterruptions.toDouble(), "agent_turns" to agentTurns.toDouble())
        return when {
            agentTurns == 0 ->
                ProbeResult(ProbeId.ECHO, Status.FAIL, "المساعد ما تكلم، فما نقدر نحكم على الصدى.", numbers, nowMs)
            selfInterruptions > MAX_SELF_INTERRUPTIONS ->
                ProbeResult(ProbeId.ECHO, Status.FAIL, "المساعد قاطع نفسه $selfInterruptions مرات: يسمع صوته من السماعات.", numbers, nowMs)
            else ->
                ProbeResult(ProbeId.ECHO, Status.PASS, "ما في صدى واضح.", numbers, nowMs)
        }
    }

    private const val SILENCE_FLOOR_DBFS = -120.0
    private const val HEARD_THRESHOLD_DBFS = -50.0

    /**
     * One reading of "can we hear the microphone right after the box starts".
     * [source] is the path that took the reading ("service" or "assistant"); readings from
     * different paths accumulate, and the probe passes when any path that started from boot heard sound.
     */
    fun bootListen(
        source: String,
        fromBoot: Boolean,
        permissionGranted: Boolean,
        pcm: ShortArray,
        secondsAfterBoot: Double,
        previous: ProbeResult?,
        nowMs: Long,
    ): ProbeResult {
        val reading = buildMap {
            put("${source}_from_boot", if (fromBoot) 1.0 else 0.0)
            put("${source}_seconds_after_boot", secondsAfterBoot)
            if (permissionGranted) put("${source}_peak_dbfs", AudioLevel.peakDbfs(pcm).coerceAtLeast(SILENCE_FLOOR_DBFS))
        }
        val numbers = (previous?.takeIf { it.id == ProbeId.BOOT_LISTEN }?.numbers ?: emptyMap()) + reading
        val heardFromBoot = numbers.keys
            .filter { it.endsWith("_from_boot") && numbers[it] == 1.0 }
            .map { it.removeSuffix("_from_boot") }
            .filter { (numbers["${it}_peak_dbfs"] ?: SILENCE_FLOOR_DBFS) > HEARD_THRESHOLD_DBFS }
        if (heardFromBoot.isNotEmpty()) {
            return ProbeResult(ProbeId.BOOT_LISTEN, Status.PASS, "يسمع بعد التشغيل عن طريق: ${heardFromBoot.joinToString("، ")}.", numbers, nowMs)
        }
        val detail = when {
            !permissionGranted -> "ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق."
            !fromBoot -> "الاستماع اشتغل يدوياً. أعد تشغيل الجهاز وافتح التطبيق عشان ينحسب."
            else -> "اشتغل مع التشغيل ($source) لكن الميكروفون صامت."
        }
        return ProbeResult(ProbeId.BOOT_LISTEN, Status.FAIL, detail, numbers, nowMs)
    }

    /** A launch through the assistant path (steering wheel button or an assist intent). */
    fun assist(action: String, previous: ProbeResult?, nowMs: Long, micPeakDbfs: Double? = null): ProbeResult {
        val count = (previous?.takeIf { it.id == ProbeId.ASSIST_BUTTON }?.numbers?.get("launch_count") ?: 0.0) + 1.0
        if (micPeakDbfs == null) {
            return ProbeResult(ProbeId.ASSIST_BUTTON, Status.PASS, "اشتغل عن طريق: $action", mapOf("launch_count" to count), nowMs)
        }
        val heard = micPeakDbfs > HEARD_THRESHOLD_DBFS
        return ProbeResult(
            ProbeId.ASSIST_BUTTON, Status.PASS,
            "اشتغل عن طريق: $action. الميكروفون وقت التشغيل: ${if (heard) "يلقط" else "صامت"}.",
            mapOf("launch_count" to count, "launch_mic_peak_dbfs" to micPeakDbfs.coerceAtLeast(SILENCE_FLOOR_DBFS)), nowMs,
        )
    }

    /**
     * The microphone tried through every audio source and input the device offers.
     * Passes when at least one of them heard sound, and names the ones that did.
     */
    fun micMatrix(permissionGranted: Boolean, readings: List<MicReading>, inputDevices: List<String>, nowMs: Long): ProbeResult {
        fun fail(detail: String, numbers: Map<String, Double> = emptyMap()) = ProbeResult(ProbeId.MIC, Status.FAIL, detail, numbers, nowMs)
        if (!permissionGranted) return fail("ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.")
        if (readings.isEmpty()) return fail("ما قدر التطبيق يفتح الميكروفون بأي طريقة.")
        val numbers = readings.associate { it.label to it.peakDbfs.coerceAtLeast(SILENCE_FLOOR_DBFS) }
        val heard = readings.filter { it.peakDbfs > HEARD_THRESHOLD_DBFS }.map { it.label }
        if (heard.isNotEmpty()) {
            return ProbeResult(ProbeId.MIC, Status.PASS, "الميكروفون يلقط عن طريق: ${heard.joinToString("، ")}.", numbers, nowMs)
        }
        val loudest = String.format(java.util.Locale.US, "%.0f", numbers.values.max())
        return fail("كل المصادر صامتة (أعلى مستوى $loudest). مداخل الجهاز: ${inputDevices.joinToString("، ").ifEmpty { "لا شيء" }}.", numbers)
    }

    /**
     * Did the microphone open at any moment of a long watch, for example while the steering
     * wheel button was held? Passes when any window heard sound, and says when and for how long.
     */
    fun micWatch(permissionGranted: Boolean, log: MicWatchLog, nowMs: Long): ProbeResult {
        fun fail(detail: String, numbers: Map<String, Double> = emptyMap()) = ProbeResult(ProbeId.MIC_WATCH, Status.FAIL, detail, numbers, nowMs)
        if (!permissionGranted) return fail("ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.")
        if (log.windowPeaksDbfs.isEmpty()) return fail("ما قدر التطبيق يفتح الميكروفون.")
        fun seconds(windows: Int) = windows * log.windowMs / 1000.0
        val heard = log.windowPeaksDbfs.withIndex().filter { it.value > HEARD_THRESHOLD_DBFS }.map { it.index }
        val numbers = buildMap {
            put("watched_s", seconds(log.windowPeaksDbfs.size))
            put("heard_s", seconds(heard.size))
            if (heard.isNotEmpty()) {
                put("first_heard_s", seconds(heard.first()))
                put("last_heard_s", seconds(heard.last() + 1))
            }
            put("max_peak_dbfs", log.windowPeaksDbfs.max().coerceAtLeast(SILENCE_FLOOR_DBFS))
            put("silenced_s", seconds(log.silencedWindows))
            put("other_recorders_s", seconds(log.otherRecorderWindows))
            put("sanafi_is_assistant", if (log.sanafiIsAssistant) 1.0 else 0.0)
        }
        if (heard.isNotEmpty()) {
            val first = String.format(java.util.Locale.US, "%.1f", seconds(heard.first()))
            val total = String.format(java.util.Locale.US, "%.1f", seconds(heard.size))
            return ProbeResult(ProbeId.MIC_WATCH, Status.PASS, "الميكروفون انفتح بعد $first ثانية ولقط صوت لمدة $total ثانية.", numbers, nowMs)
        }
        return if (log.silencedWindows > 0) {
            fail("صامت طول المراقبة، والنظام كاتم التسجيل: تطبيق ثاني ماسك الميكروفون أو الميكروفون مقفول من الخصوصية.", numbers)
        } else {
            fail("الميكروفون ما انفتح طول المراقبة.", numbers)
        }
    }

    private const val MIN_CALLS_HEARD = 3

    /**
     * The wake word over music. A calling run counts how many of the five calls were heard; a
     * quiet run counts false alarms. Runs accumulate: the probe passes when at least three calls
     * were heard and no quiet run raised a false alarm.
     */
    fun wake(permissionGranted: Boolean, run: WakeRun?, error: String?, previous: ProbeResult?, nowMs: Long): ProbeResult {
        val before = previous?.takeIf { it.id == ProbeId.WAKE_OVER_MUSIC }?.numbers ?: emptyMap()
        fun result(status: Status, detail: String, numbers: Map<String, Double> = before) = ProbeResult(ProbeId.WAKE_OVER_MUSIC, status, detail, numbers, nowMs)
        if (!permissionGranted) return result(Status.FAIL, "ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.")
        if (error != null || run == null) return result(Status.FAIL, "ما اشتغل كاشف النداء: ${error ?: "سبب غير معروف"}")
        val numbers = before + when (run.mode) {
            WakeMode.CALLING -> mapOf(
                "detections" to run.detections.toDouble(),
                "max_score" to run.maxScore,
                "avg_cpu_ms_per_frame" to run.avgMsPerFrame,
                "listened_s" to run.listenedSeconds,
            )
            WakeMode.QUIET -> mapOf(
                "false_alarms" to run.detections.toDouble(),
                "quiet_max_score" to run.maxScore,
                "quiet_listened_s" to run.listenedSeconds,
            )
        }
        val heard = numbers["detections"]?.toInt()
        val falseAlarms = numbers["false_alarms"]?.toInt()
        return when {
            heard == null -> result(Status.NOT_RUN, "بدون نداء: انطلق بالغلط $falseAlarms مرات. باقي «اختبار النداء».", numbers)
            heard < MIN_CALLS_HEARD -> result(Status.FAIL, "سمع النداء $heard مرات بس (المطلوب $MIN_CALLS_HEARD من ٥ على الأقل).", numbers)
            falseAlarms != null && falseAlarms > 0 -> result(Status.FAIL, "سمع النداء $heard مرات، لكن انطلق بالغلط $falseAlarms مرات والأغنية شغالة.", numbers)
            falseAlarms == null -> result(Status.PASS, "سمع النداء $heard مرات والأغنية شغالة. باقي «اختبار بدون نداء».", numbers)
            else -> result(Status.PASS, "سمع النداء $heard مرات والأغنية شغالة، وما انطلق بالغلط.", numbers)
        }
    }

    /** Phone-call ability. Undecided (NOT_RUN) until the owner answers whether the test call rang. */
    fun telephony(hasTelephony: Boolean, simReady: Boolean, canResolveCall: Boolean, rang: Boolean?, nowMs: Long): ProbeResult {
        val numbers = buildMap {
            put("has_telephony", if (hasTelephony) 1.0 else 0.0)
            put("sim_ready", if (simReady) 1.0 else 0.0)
            put("can_resolve_call", if (canResolveCall) 1.0 else 0.0)
            if (rang != null) put("rang", if (rang) 1.0 else 0.0)
        }
        fun result(status: Status, detail: String) = ProbeResult(ProbeId.TELEPHONY, status, detail, numbers, nowMs)
        return when {
            !hasTelephony -> result(Status.FAIL, "الجهاز ما يدعم المكالمات الهاتفية.")
            !simReady -> result(Status.FAIL, "الشريحة مو جاهزة أو ما تدعم المكالمات.")
            !canResolveCall -> result(Status.FAIL, "ما في تطبيق اتصال على الجهاز.")
            rang == null -> result(Status.NOT_RUN, "الجهاز يدعم الاتصال نظرياً. جرّب مكالمة على رقمك.")
            rang -> result(Status.PASS, "المكالمة طلعت ورنّ الجوال.")
            else -> result(Status.FAIL, "المكالمة ما وصلت للجوال.")
        }
    }
}
