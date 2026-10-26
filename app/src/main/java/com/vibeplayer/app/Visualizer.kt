package com.vibeplayer.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The latest sound analysis. PlaybackService fills it in while music plays,
 * and the visualizer screens read it to draw the bars.
 */
object VisualizerData {
    /** How many bars the visualizer has, from low notes (left) to high notes (right). */
    const val BANDS = 40

    /** One number per bar, from 0 (silent) to 1 (loud). */
    @Volatile
    var bands: FloatArray = FloatArray(BANDS)
}

/**
 * Listens to the music as it flows to the speakers, without changing it, and works out
 * how loud each pitch range is (a "spectrum"). This needs no microphone permission,
 * because it looks at the music itself and not at the room.
 */
@UnstableApi
class VisualizerProcessor : BaseAudioProcessor() {

    private companion object {
        const val FFT_SIZE = 4096 // how many samples are analysed at once
        const val HOP = 1024      // a new analysis every this many samples (about 43 times a second)
    }

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val ring = FloatArray(FFT_SIZE)
    private var ringCount = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val bandStart = IntArray(VisualizerData.BANDS)
    private val bandEnd = IntArray(VisualizerData.BANDS)
    private val bandTilt = FloatArray(VisualizerData.BANDS)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> true
            else -> false
        }
        // For any other kind of audio, switch this processor off instead of breaking playback.
        if (!supported || inputAudioFormat.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        setupBands(inputAudioFormat.sampleRate)
        ringCount = 0
        return inputAudioFormat
    }

    /** Decides which parts of the spectrum belong to each bar. */
    private fun setupBands(sampleRate: Int) {
        val minFreq = 40f
        val maxFreq = min(16000f, sampleRate / 2f * 0.95f)
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val bands = VisualizerData.BANDS
        for (i in 0 until bands) {
            val low = minFreq * (maxFreq / minFreq).pow(i.toFloat() / bands)
            val high = minFreq * (maxFreq / minFreq).pow((i + 1).toFloat() / bands)
            val start = (low / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            bandStart[i] = start
            bandEnd[i] = max(start + 1, (high / binHz).toInt()).coerceAtMost(FFT_SIZE / 2)
            // High notes carry less energy in music, so lift them a little to balance the bars.
            bandTilt[i] = max(0f, 2.5f * log2(sqrt(low * high) / 200f))
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // Look at a copy of the music...
        analyse(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        // ...and pass the original on unchanged.
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }

    private fun analyse(buffer: ByteBuffer) {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val frameBytes = bytesPerSample * channels
        while (buffer.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) sum += readSample(buffer)
            pushSample(sum / channels)
        }
    }

    private fun readSample(buffer: ByteBuffer): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buffer.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        C.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
        else -> buffer.float
    }

    private fun pushSample(sample: Float) {
        ring[ringCount++] = sample
        if (ringCount == FFT_SIZE) {
            computeSpectrum()
            // Keep the newest part so the next analysis overlaps with this one.
            System.arraycopy(ring, HOP, ring, 0, FFT_SIZE - HOP)
            ringCount = FFT_SIZE - HOP
        }
    }

    private fun computeSpectrum() {
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[i] * window[i]
            im[i] = 0f
        }
        fft()
        val bands = VisualizerData.BANDS
        val result = FloatArray(bands)
        val scale = 4f / FFT_SIZE // with this window, a full-volume tone measures N/4
        for (b in 0 until bands) {
            var peak = 0f
            for (k in bandStart[b] until bandEnd[b]) {
                val magnitude = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
                if (magnitude > peak) peak = magnitude
            }
            val decibels = 20f * log10(peak + 1e-7f) + bandTilt[b]
            result[b] = ((decibels + 72f) / 56f).coerceIn(0f, 1f)
        }
        VisualizerData.bands = result
    }

    /** A standard Fast Fourier Transform: turns sound waves into "how much of each pitch". */
    private fun fft() {
        val n = FFT_SIZE
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * Math.PI / length
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            val half = length / 2
            var start = 0
            while (start < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val vRe = re[b] * wRe - im[b] * wIm
                    val vIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] = re[a] + vRe
                    im[a] = im[a] + vIm
                    val nextRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
