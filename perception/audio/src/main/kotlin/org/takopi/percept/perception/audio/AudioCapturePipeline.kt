package org.takopi.percept.perception.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §3.4 capture front end: one AudioRecord at 16 kHz mono PCM16; the capture
 * thread only appends into the engine's ring buffer, a separate processing
 * thread drains ASR windows and tag frames so slow inference can never cause
 * capture dropouts.
 */
class AudioCapturePipeline(
    private val engine: AudioPerceptionEngine,
    private val sampleRate: Int = AudioPerceptionEngine.DEFAULT_SAMPLE_RATE,
    private val readChunkSamples: Int = DEFAULT_READ_CHUNK_SAMPLES,
    private val processIntervalMillis: Long = DEFAULT_PROCESS_INTERVAL_MILLIS,
) {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var processThread: Thread? = null

    /** Caller must hold RECORD_AUDIO; the foreground service checks before starting. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "pipeline already running" }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate * Short.SIZE_BYTES),
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize"
        }
        record = audioRecord
        audioRecord.startRecording()

        captureThread = Thread({
            val chunk = ShortArray(readChunkSamples)
            while (running.get()) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read > 0) {
                    engine.append(chunk.copyOf(read))
                }
            }
        }, "percept-audio-capture").also(Thread::start)

        processThread = Thread({
            while (running.get()) {
                engine.processAvailable()
                try {
                    Thread.sleep(processIntervalMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "percept-audio-process").also(Thread::start)
    }

    fun stop(): AudioRunCounters {
        check(running.compareAndSet(true, false)) { "pipeline not running" }
        captureThread?.join()
        processThread?.interrupt()
        processThread?.join()
        record?.let { audioRecord ->
            audioRecord.stop()
            audioRecord.release()
        }
        record = null
        return engine.finish()
    }

    companion object {
        /** 80 ms read chunks at 16 kHz. */
        const val DEFAULT_READ_CHUNK_SAMPLES: Int = 1_280
        const val DEFAULT_PROCESS_INTERVAL_MILLIS: Long = 500
    }
}
