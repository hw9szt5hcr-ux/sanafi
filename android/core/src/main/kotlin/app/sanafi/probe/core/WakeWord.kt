package app.sanafi.probe.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * The openWakeWord pipeline (github.com/dscripka/openWakeWord, Apache-2.0), written here on
 * ONNX Runtime instead of taken from a third-party Android port: 16 kHz mono audio goes in,
 * and every 80 ms frame gives one score between 0 and 1 for the wake word.
 *
 * Three models run in a row: a mel spectrogram, a speech embedding over the last 76 mel frames,
 * and the wake-word model over the last 16 embeddings.
 */
class WakeWordDetector(melModel: ByteArray, embeddingModel: ByteArray, wakeModel: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val mel = env.createSession(melModel, options())
    private val embedding = env.createSession(embeddingModel, options())
    private val wake = env.createSession(wakeModel, options())

    private val pending = ShortArray(FRAME_SAMPLES)
    private var pendingCount = 0
    private val context = ShortArray(CONTEXT_SAMPLES)
    private val melWindow = FloatArray(MEL_FRAMES * MEL_BINS) { 1f }
    private val embeddings = ArrayDeque<FloatArray>()

    /** Takes any amount of audio; returns one score for each 80 ms frame it completed. */
    fun feed(samples: ShortArray): List<Float> {
        val scores = ArrayList<Float>()
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(FRAME_SAMPLES - pendingCount, samples.size - offset)
            System.arraycopy(samples, offset, pending, pendingCount, take)
            pendingCount += take
            offset += take
            if (pendingCount == FRAME_SAMPLES) {
                scores += score(pending)
                pendingCount = 0
            }
        }
        return scores
    }

    private fun score(frame: ShortArray): Float {
        val audio = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
        for (i in 0 until CONTEXT_SAMPLES) audio[i] = context[i].toFloat()
        for (i in 0 until FRAME_SAMPLES) audio[CONTEXT_SAMPLES + i] = frame[i].toFloat()
        System.arraycopy(frame, FRAME_SAMPLES - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES)

        val newMel = run(mel, audio, longArrayOf(1, audio.size.toLong()))
        for (i in newMel.indices) newMel[i] = newMel[i] / 10f + 2f
        val keep = melWindow.size - newMel.size
        System.arraycopy(melWindow, newMel.size, melWindow, 0, keep)
        System.arraycopy(newMel, 0, melWindow, keep, newMel.size)

        embeddings.addLast(run(embedding, melWindow, longArrayOf(1, MEL_FRAMES.toLong(), MEL_BINS.toLong(), 1)))
        if (embeddings.size > EMBEDDINGS) embeddings.removeFirst()
        if (embeddings.size < EMBEDDINGS) return 0f

        val features = FloatArray(EMBEDDINGS * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, e -> System.arraycopy(e, 0, features, i * EMBEDDING_SIZE, EMBEDDING_SIZE) }
        return run(wake, features, longArrayOf(1, EMBEDDINGS.toLong(), EMBEDDING_SIZE.toLong()))[0]
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    override fun close() {
        wake.close()
        embedding.close()
        mel.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1280
        private const val CONTEXT_SAMPLES = 480
        private const val MEL_FRAMES = 76
        private const val MEL_BINS = 32
        private const val EMBEDDINGS = 16
        private const val EMBEDDING_SIZE = 96
    }
}

/**
 * Turns the stream of scores into a count of calls: a score at or above [threshold] is one
 * detection, and the next [refractoryFrames] frames (two seconds) belong to the same call.
 */
class WakeCounter(private val threshold: Float = 0.5f, private val refractoryFrames: Int = 25) {
    var detections = 0
        private set
    var maxScore = 0f
        private set
    private var cooldown = 0

    /** Returns true when this score starts a new detection. */
    fun onScore(score: Float): Boolean {
        if (score > maxScore) maxScore = score
        if (cooldown > 0) {
            cooldown--
            return false
        }
        if (score < threshold) return false
        detections++
        cooldown = refractoryFrames
        return true
    }
}

/** CALLING: the owner says the wake word five times over music. QUIET: music only, nobody calls. */
enum class WakeMode { CALLING, QUIET }

data class WakeRun(
    val mode: WakeMode,
    val detections: Int,
    val maxScore: Double,
    val avgMsPerFrame: Double,
    val listenedSeconds: Double,
)
