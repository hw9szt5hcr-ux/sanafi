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
}
