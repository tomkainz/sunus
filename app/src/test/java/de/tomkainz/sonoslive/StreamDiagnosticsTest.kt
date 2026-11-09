package de.tomkainz.sonoslive

import de.tomkainz.sonoslive.adb.AdbManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class StreamDiagnosticsTest {

    /** Hands out [text], then fails the way AdbStream does when a command has ended. */
    private class ClosingStream(text: String) : InputStream() {
        private val data = ByteArrayInputStream(text.toByteArray())
        override fun read(): Int = data.read().takeIf { it >= 0 } ?: throw IOException("Stream closed.")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = data.read(b, off, len)
            if (read < 0) throw IOException("Stream closed.")
            return read
        }
    }

    /** Hands out one chunk per read. Reading past the last one fails the test: the real stream would hang there. */
    private class ChunkStream(private vararg val chunks: String) : InputStream() {
        private var next = 0
        override fun read(): Int = throw AssertionError("not used")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(next < chunks.size) { "read past the end mark, this would hang" }
            val bytes = chunks[next++].toByteArray()
            bytes.copyInto(b, off)
            return bytes.size
        }
    }

    private val mark = AdbManager.END_MARK

    @Test fun readingStopsAtTheMark() =
        assertEquals("== Helper ==\r\n", AdbManager.readUntilMark(ChunkStream("== Helper ==\r\n$mark\r\n")))

    @Test fun aMarkSplitAcrossReadsIsFound() = assertEquals(
        "out\n", AdbManager.readUntilMark(ChunkStream("out\n${mark.take(4)}", mark.drop(4)))
    )

    @Test fun outputIsKeptIfTheStreamClosesBeforeTheMark() =
        assertEquals("== Helper ==\n", AdbManager.readUntilMark(ClosingStream("== Helper ==\n")))

    @Test fun aStreamThatClosesAtOnceIsEmptyNotAnError() =
        assertEquals("", AdbManager.readUntilMark(ClosingStream("")))

    @Test fun aNormalEndWorksToo() =
        assertEquals("done", AdbManager.readUntilMark(ByteArrayInputStream("done".toByteArray())))

    @Test fun aHealthyStreamIsAtRealTime() = assertEquals(
        "614 s, 117.9 MB, 100.0% of real time, longest write 120 ms, 0 chunks dropped",
        LiveStreamServer.clientFacts(614.0, 117_888_000, 120, 0)
    )

    @Test fun aStalledStreamFallsBehind() = assertEquals(
        "100 s, 9.6 MB, 50.0% of real time, longest write 8000 ms, 42 chunks dropped",
        LiveStreamServer.clientFacts(100.0, 9_600_000, 8000, 42)
    )

    @Test fun noTimeYetDoesNotDivideByZero() = assertEquals(
        "0 s, 0.0 MB, 0.0% of real time, longest write 0 ms, 0 chunks dropped",
        LiveStreamServer.clientFacts(0.0, 0, 0, 0)
    )
}
