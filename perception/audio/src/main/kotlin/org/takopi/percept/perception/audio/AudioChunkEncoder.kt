package org.takopi.percept.perception.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes one PCM16 chunk into the bytes stored as a DA artifact. */
interface AudioChunkEncoder {
    /** MIME recorded in the audio-chunk payload, e.g. "audio/ogg; codecs=opus". */
    val contentType: String

    /** Codec identity recorded in the payload for provenance. */
    val codecId: String

    fun encode(samples: ShortArray, sampleRate: Int): ByteArray
}

/** Uncompressed fallback: the episodic record survives even without a codec. */
class PcmChunkEncoder : AudioChunkEncoder {
    override val contentType: String = "audio/L16;rate=16000;channels=1"
    override val codecId: String = "pcm-s16le"

    override fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return bytes
    }
}

/**
 * Ogg/Opus at 24 kbps mono via the platform codec — ~180 KB per minute
 * against 1.9 MB raw, so bundles stay uploadable.
 */
class MediaCodecOggOpusEncoder(
    private val cacheDir: File,
    private val bitrate: Int = 24_000,
) : AudioChunkEncoder {
    override val contentType: String = "audio/ogg; codecs=opus"
    override val codecId: String = "oggopus-mediacodec-${bitrate / 1000}k"

    override fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
            .apply { setInteger(MediaFormat.KEY_BIT_RATE, bitrate) }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val outputFile = File.createTempFile("percept-chunk", ".ogg", cacheDir)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            val pcmBytes = PcmChunkEncoder().encode(samples, sampleRate)
            var inputOffset = 0
            var trackIndex = -1
            var muxerStarted = false
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            var presentationUs = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        val length = minOf(buffer.remaining(), pcmBytes.size - inputOffset)
                        if (length <= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            buffer.put(pcmBytes, inputOffset, length)
                            codec.queueInputBuffer(inputIndex, 0, length, presentationUs, 0)
                            inputOffset += length
                            presentationUs = inputOffset.toLong() * 1_000_000L / (2L * sampleRate)
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                        if (bufferInfo.size > 0 && muxerStarted &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            muxer.stop()
            return outputFile.readBytes()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
            outputFile.delete()
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L

        fun isSupported(): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
                }
            }
    }
}

object AudioChunkEncoders {
    /** Opus when the platform has an encoder for it; raw PCM otherwise. */
    fun createBest(context: Context): AudioChunkEncoder =
        if (MediaCodecOggOpusEncoder.isSupported()) {
            MediaCodecOggOpusEncoder(context.cacheDir)
        } else {
            PcmChunkEncoder()
        }
}
