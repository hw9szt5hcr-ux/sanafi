package app.sanafi.probe.core

/** Turns raw observations from a probe into the result that is stored and reported. */
object ProbeReducer {
    private const val MAX_SELF_INTERRUPTIONS = 1

    fun mic(permissionGranted: Boolean, pcm: ShortArray, nowMs: Long): ProbeResult {
        if (!permissionGranted) {
            return ProbeResult(ProbeId.MIC, Status.FAIL, "ما انعطت الصلاحية للميكروفون. اسمح بها من إعدادات التطبيق.", atEpochMs = nowMs)
        }
        if (AudioLevel.isSilent(pcm)) {
            return ProbeResult(ProbeId.MIC, Status.FAIL, "التسجيل صامت: الميكروفون ما يوصّل صوت للتطبيق.", atEpochMs = nowMs)
        }
        val peak = AudioLevel.peakDbfs(pcm)
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
    fun assist(action: String, previous: ProbeResult?, nowMs: Long): ProbeResult {
        val count = (previous?.takeIf { it.id == ProbeId.ASSIST_BUTTON }?.numbers?.get("launch_count") ?: 0.0) + 1.0
        return ProbeResult(ProbeId.ASSIST_BUTTON, Status.PASS, "اشتغل عن طريق: $action", mapOf("launch_count" to count), nowMs)
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
