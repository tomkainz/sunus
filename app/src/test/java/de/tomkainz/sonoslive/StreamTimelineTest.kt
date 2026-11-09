package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture delivers blocks of 1024 frames (21.3 ms). Each case says when a block
 * arrives, or that it is lost, and checks what the stream sends in its place.
 */
class StreamTimelineTest {

    private val rate = 48000
    private val chunk = 1024

    /** When block [k] is complete at the source; capture starts at [startMs]. */
    private fun produced(k: Int, startMs: Long) = startMs + (k + 1) * chunk * 1000L / rate

    private class Run(
        val timeline: StreamTimeline,
        val audioMs: Long,
        val silenceMs: Long,
        val droppedMs: Long,
        /** Each time silence went out: when, and how much (ms). */
        val fills: List<Pair<Long, Long>>,
        val endBehindMs: Long,
        /** Audio dropped after this point in time, to catch trimming that never stops. */
        val droppedAfter: (Long) -> Long
    )

    /**
     * Runs [ms] milliseconds with the pump looking every millisecond. [arrival] maps a block
     * and the time it is complete to when it reaches the pump, or null when it is lost.
     * [pumpHeld] is a time span in which the pump does not run at all.
     */
    private fun run(
        ms: Long,
        startMs: Long = 100,
        pumpHeld: LongRange? = null,
        arrival: (Int, Long) -> Long? = { _, t -> t }
    ): Run {
        val timeline = StreamTimeline(rate, 0)
        val arrivals = sortedMapOf<Long, Int>()
        var k = 0
        while (produced(k, startMs) <= ms) {
            arrival(k, produced(k, startMs))?.let { arrivals.merge(it, 1, Int::plus) }
            k++
        }
        var audio = 0L
        val fills = mutableListOf<Pair<Long, Long>>()
        val drops = mutableListOf<Pair<Long, Long>>()
        for (t in 0..ms) {
            if (pumpHeld != null && t in pumpHeld) continue
            // Blocks that came while the pump was held are waiting for it.
            val waiting = if (pumpHeld != null && t == pumpHeld.last + 1) {
                arrivals.subMap(pumpHeld.first, t + 1).values.sum()
            } else arrivals[t] ?: 0
            repeat(waiting) {
                val keep = timeline.onAudio(chunk, t)
                audio += keep
                if (keep < chunk) drops += t to (chunk - keep).toLong()
            }
            val due = timeline.silenceDue(t)
            if (due > 0) fills += t to timeline.millis(due)
        }
        return Run(
            timeline,
            timeline.millis(audio),
            timeline.millis(timeline.silenceFrames),
            timeline.millis(timeline.droppedFrames),
            fills,
            timeline.millis(timeline.behindFrames(ms)),
            { after -> timeline.millis(drops.filter { it.first > after }.sumOf { it.second }) }
        )
    }

    @Test
    fun steadyAudioGetsNoSilence() {
        val r = run(60_000)
        assertEquals(0, r.silenceMs)
        assertEquals(0, r.droppedMs)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }

    @Test
    fun lateAudioIsWaitedFor() {
        // The ADB connection stalls for 1.3 s; everything arrives at once afterwards.
        val r = run(20_000) { _, t -> if (t in 5000 until 6300) 6300 else t }
        assertEquals(0, r.silenceMs)
        assertEquals(0, r.droppedMs)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }

    @Test
    fun lostAudioIsReplacedBySilenceSoonAfter() {
        // 1.3 s lost, like before the drop on 08.11.2026 at 18:03.
        val r = run(20_000) { _, t -> if (t in 5000 until 6300) null else t }
        assertTrue("silence ${r.silenceMs}", r.silenceMs in 1270..1330)
        assertEquals(1, r.fills.size)
        // Once audio has flowed again for 300 ms; the Play:1 hung up 14 s after the stall.
        assertTrue("filled at ${r.fills[0].first}", r.fills[0].first in 6550..6700)
        assertEquals(0, r.droppedMs)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }

    @Test
    fun aLongPauseIsFilledFromTheStartOfTheGap() {
        // No audio for 3 s. Before, silence only started after 1.5 s and the stream stayed
        // 1.5 s short; on 07.11. and 08.11. the Play:1 hung up 14.2 s later.
        val r = run(20_000) { _, t -> if (t in 5000 until 8000) null else t }
        assertTrue("silence ${r.silenceMs}", r.silenceMs in 2970..3030)
        // 1.5 s after the last block before the gap, all of it at once.
        val first = r.fills.first()
        assertTrue("first fill $first", first.first in 6470..6530 && first.second in 1460..1530)
        assertEquals(0, r.droppedMs)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }

    @Test
    fun lateAudioAfterFillingIsTrimmedOnceAndThenFlows() {
        // 3 s stall; the helper held the first 2 s and lost the last one. That comes in a
        // burst after silence already went out for all 3 s.
        val r = run(30_000) { _, t ->
            when (t) {
                in 5000 until 7000 -> 8000
                in 7000 until 8000 -> null
                else -> t
            }
        }
        assertTrue("silence ${r.silenceMs}", r.silenceMs in 2970..3030)
        // 2 s too much: up to 1 s ahead is fine, then back down to 0.5 s, which takes
        // another 0.5 s of audio after the burst. After that, nothing more is dropped.
        assertTrue("dropped ${r.droppedMs}", r.droppedMs in 1450..1550)
        assertEquals("drops once back at 0.5 s", 0, r.droppedAfter(8600))
        assertTrue("ahead ${-r.endBehindMs}", -r.endBehindMs in 470..530)
    }

    @Test
    fun waitingForTheFirstAudioIsNoGap() {
        // All apps: ADB and the helper take 1.6 s before the first audio.
        val r = run(10_000, startMs = 1600)
        assertTrue("silence ${r.silenceMs}", r.silenceMs in 1..200)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }

    @Test
    fun jitterAloneGetsNoSilence() {
        // Blocks come in bunches every 150 ms, as over a busy connection.
        val r = run(30_000) { _, t -> (t / 150 + 1) * 150 }
        assertEquals(0, r.silenceMs)
        assertEquals(0, r.droppedMs)
    }

    @Test
    fun smallLossesAddUpAndAreFilled() {
        // One block in 47 lost: about 21 ms a second, 640 ms in 30 s.
        val r = run(30_100) { k, t -> if (k % 47 == 46) null else t }
        val blocks = 30_000L * rate / 1000 / chunk
        val lost = blocks / 47 * chunk * 1000 / rate
        assertTrue("silence ${r.silenceMs}, lost $lost", r.silenceMs in lost - 260..lost + 30)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..260)
    }

    @Test
    fun aPumpHeldUpForLongFillsOnlyABoundedAmount() {
        val r = run(20_000, pumpHeld = 5000L until 15_000L) { _, t -> if (t in 5000 until 15_000) null else t }
        assertTrue("silence ${r.silenceMs}", r.silenceMs in 2990..3030)
        assertTrue("forgotten", r.timeline.millis(r.timeline.forgottenFrames) in 6950..7050)
        assertTrue("behind ${r.endBehindMs}", r.endBehindMs in -30..30)
    }
}
