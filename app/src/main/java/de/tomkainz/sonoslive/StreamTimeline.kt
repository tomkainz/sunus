package de.tomkainz.sonoslive

/**
 * Keeps the stream on real time.
 *
 * The speaker plays at a steady pace from what it has buffered. Audio that comes late only
 * dips into that buffer, and the catch-up afterwards fills it again: in the phone logs,
 * Wi-Fi stalls of 2.4 s and more passed without the speaker hanging up. Audio that never
 * comes (the capture stalled and lost it) leaves the buffer short for good. On 07.11. and
 * 08.11.2026 the Play:1 hung up 14.2 s after such a stall, both times it could be dated.
 *
 * So the stream waits for late audio and puts silence in for what is still missing:
 * - Audio flows again for [settleMs] after a gap, but the stream is still more than
 *   [slackMs] behind: that audio is lost, the silence goes in at once.
 * - No audio at all for [graceMs]: the source is gone. Silence for the whole gap at once,
 *   then in real time until audio returns.
 * - Late audio after that would put the stream ahead of real time. Up to [aheadMs] is kept
 *   (the speaker just has more buffered); beyond that, audio is dropped down to half of it,
 *   so the delay cannot keep growing.
 *
 * Times are milliseconds of a monotonic clock, amounts are frames. Used by one thread only.
 */
class StreamTimeline(
    private val rate: Int,
    createdMs: Long,
    private val graceMs: Long = 1500,
    private val slackMs: Long = 250,
    private val settleMs: Long = 300,
    private val aheadMs: Long = 1000,
    private val maxFillMs: Long = 3000
) {
    companion object {
        /** Longer than this without audio counts as a gap; chunks normally come every 21 ms. */
        const val GAP_MS = 200L
    }

    /** Real time counts from here, once the first frame went out. */
    private var startMs = -1L
    private var startFrames = 0L
    private var lastAudioMs = createdMs
    private var resumedMs = createdMs
    private var trimming = false

    /** Everything that went out: audio and silence. */
    var sentFrames = 0L
        private set
    var silenceFrames = 0L
        private set
    var droppedFrames = 0L
        private set
    /** Missing audio that was given up on instead of filled (the pump was held up for long). */
    var forgottenFrames = 0L
        private set

    private fun frames(ms: Long) = ms * rate / 1000
    fun millis(frames: Long) = frames * 1000 / rate

    private fun expected(nowMs: Long) = startFrames + frames(nowMs - startMs)

    /** How far the stream is behind real time; negative when it is ahead. */
    fun behindFrames(nowMs: Long): Long = if (startMs < 0) 0 else expected(nowMs) - sentFrames

    /** No audio for at least [graceMs]: silence is going out in its place. */
    fun sourceGone(nowMs: Long) = nowMs - lastAudioMs >= graceMs

    /** Audio arrived. Returns how many of its frames to send; the rest would run too far ahead. */
    fun onAudio(frames: Int, nowMs: Long): Int {
        if (nowMs - lastAudioMs > GAP_MS) resumedMs = nowMs
        lastAudioMs = nowMs
        if (startMs < 0) {
            startMs = nowMs
            startFrames = 0
        }
        var keep = frames.toLong()
        if (trimming || sentFrames + keep - expected(nowMs) > frames(aheadMs)) {
            // Drop whole chunks until the stream is back at half the limit. Trimming only
            // at the limit itself would drop every other chunk from then on.
            keep = (expected(nowMs) + frames(aheadMs / 2) - sentFrames).coerceIn(0, keep)
            trimming = keep == 0L
            droppedFrames += frames - keep
        }
        sentFrames += keep
        return keep.toInt()
    }

    /** Silence to send now, in frames; counted as sent. Call it every tick. */
    fun silenceDue(nowMs: Long): Long {
        if (startMs < 0) {
            // Waiting for the first audio is not a gap; from here on, silence in real time.
            if (nowMs - lastAudioMs < graceMs) return 0
            startMs = nowMs
            startFrames = 0
            return 0
        }
        val behind = expected(nowMs) - sentFrames
        if (behind <= 0) return 0
        val flowing = nowMs - lastAudioMs <= GAP_MS
        var due = when {
            sourceGone(nowMs) -> behind
            behind <= frames(slackMs) -> 0
            flowing && nowMs - resumedMs >= settleMs -> behind
            else -> 0
        }
        if (due > frames(maxFillMs)) {
            // Held up for long (say, the pump did not run): filling all of it would only
            // pile up delay at the speaker. Fill a bounded amount and forget the rest.
            val forget = due - frames(maxFillMs)
            startFrames -= forget
            forgottenFrames += forget
            due = frames(maxFillMs)
        }
        sentFrames += due
        silenceFrames += due
        return due
    }
}
