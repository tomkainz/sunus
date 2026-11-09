package de.tomkainz.sonoslive.airplay

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ALAC frames without compression: valid for every decoder, with no encoder to carry
 * around, and they cost only the bandwidth the stream has anyway.
 */
object Alac {
    const val FRAMES = 352
    const val SIZE = 1412

    /** Packs [FRAMES] stereo frames: 23 header bits, 16-bit big-endian samples, 3 end bits. */
    fun encode(pcm: ShortArray, offset: Int, out: ByteArray) {
        // 3 bits element type (stereo), 4 instance tag, 12 unused, no size, no extra bits, not compressed.
        out[0] = 0x20
        out[1] = 0
        out[2] = 0x02
        var at = 2
        for (i in offset until offset + FRAMES * 2) {
            val s = pcm[i].toInt() and 0xFFFF
            out[at] = (out[at].toInt() or (s ushr 15)).toByte()
            out[at + 1] = (s ushr 7).toByte()
            out[at + 2] = (s shl 1).toByte()
            at += 2
        }
        // The end tag, 111, across the last byte boundary.
        out[at] = (out[at].toInt() or 1).toByte()
        out[at + 1] = 0xC0.toByte()
    }

    /** The decoder setup the classic AirPlay handshake names: frame length, depth, rate. */
    const val FMTP = "$FRAMES 0 16 40 10 14 2 255 0 0 44100"
}

/**
 * 48 kHz to 44.1 kHz, as AirPlay's common format wants it. A windowed sinc with 147
 * phases; 64 taps per phase keep what is audible and reject what would fold back.
 */
class Resampler {
    private val history = ShortArray(TAPS * 2)
    private var phase = 0

    /** Interleaved stereo in, interleaved stereo out; returns the number of samples written. */
    fun process(input: ShortArray, inputCount: Int, output: ShortArray): Int {
        var written = 0
        var read = 0
        val frames = inputCount / 2
        while (true) {
            // The output sample at this phase needs the input up to this frame.
            while (phase >= UP) {
                if (read >= frames) return written
                System.arraycopy(history, 2, history, 0, history.size - 2)
                history[history.size - 2] = input[read * 2]
                history[history.size - 1] = input[read * 2 + 1]
                read++
                phase -= UP
            }
            val row = FILTER[phase]
            var left = 0f
            var right = 0f
            for (t in 0 until TAPS) {
                val c = row[t]
                left += c * history[t * 2]
                right += c * history[t * 2 + 1]
            }
            output[written++] = clip(left)
            output[written++] = clip(right)
            phase += DOWN
        }
    }

    private fun clip(v: Float): Short = when {
        v >= 32767f -> Short.MAX_VALUE
        v <= -32768f -> Short.MIN_VALUE
        else -> Math.round(v).toShort()
    }

    companion object {
        const val UP = 147
        const val DOWN = 160
        private const val TAPS = 64

        /** At most this many output samples can come from [inputSamples] interleaved input samples. */
        fun outputRoom(inputSamples: Int) = inputSamples * UP / DOWN + 8

        private val FILTER: Array<FloatArray> = run {
            // Half way down at 21 kHz: flat to about 19 kHz, gone by the time anything would fold back.
            val cutoff = 21000.0 / 24000.0
            Array(UP) { phase ->
                val row = FloatArray(TAPS)
                var sum = 0.0
                for (t in 0 until TAPS) {
                    // Distance in input frames from this tap to the wanted point in time.
                    val x = t - (TAPS / 2.0 - 1) - phase.toDouble() / UP
                    val argument = PI * x * cutoff
                    val sinc = if (kotlin.math.abs(argument) < 1e-9) 1.0 else sin(argument) / argument
                    val w = (x / (TAPS / 2.0)).coerceIn(-1.0, 1.0)
                    val window = 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
                    val c = sinc * window
                    row[t] = c.toFloat()
                    sum += c
                }
                for (t in 0 until TAPS) row[t] = (row[t] / sum).toFloat()
                row
            }
        }
    }
}

/** Keeps a little audio ready, so the steady packets survive capture that arrives in bursts. */
class AudioRing(private val capacityFrames: Int) {
    private val data = ShortArray(capacityFrames * 2)
    private var readAt = 0
    private var size = 0

    @Synchronized
    fun frames() = size

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        val frames = count / 2
        for (i in 0 until frames) {
            if (size == capacityFrames) {
                readAt = (readAt + 1) % capacityFrames
                size--
            }
            val w = (readAt + size) % capacityFrames
            data[w * 2] = samples[i * 2]
            data[w * 2 + 1] = samples[i * 2 + 1]
            size++
        }
    }

    /** All or nothing: false leaves [out] alone. */
    @Synchronized
    fun read(out: ShortArray, frames: Int): Boolean {
        if (size < frames) return false
        for (i in 0 until frames) {
            val r = (readAt + i) % capacityFrames
            out[i * 2] = data[r * 2]
            out[i * 2 + 1] = data[r * 2 + 1]
        }
        readAt = (readAt + frames) % capacityFrames
        size -= frames
        return true
    }

    /** Throws away the oldest audio until [frames] are left. */
    @Synchronized
    fun trimTo(frames: Int) {
        if (size > frames) {
            readAt = (readAt + size - frames) % capacityFrames
            size = frames
        }
    }
}

/** AirPlay's clock format: seconds since 1900, then the fraction in 32 bits. */
object NtpClock {
    private const val EPOCH_OFFSET = 0x83AA7E80L
    private val startNanos = System.nanoTime()
    private val startMillis = System.currentTimeMillis()

    /** Steady, so a clock change on the phone cannot move the playing position. */
    fun now(): Long {
        val nanos = startMillis * 1_000_000 + (System.nanoTime() - startNanos)
        val seconds = nanos / 1_000_000_000
        val fraction = ((nanos % 1_000_000_000) shl 32) / 1_000_000_000
        return ((seconds + EPOCH_OFFSET) shl 32) or fraction
    }
}
