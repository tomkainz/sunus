package de.tomkainz.sonoslive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {

    private val header = LiveStreamServer.wavHeader()
    private val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

    private fun ascii(at: Int, length: Int) = String(header, at, length, Charsets.US_ASCII)

    @Test fun length() = assertEquals(44, header.size)

    @Test fun chunks() {
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVEfmt ", ascii(8, 8))
        assertEquals("data", ascii(36, 4))
    }

    @Test fun formatIs48kStereo16BitPcm() {
        assertEquals(16, le.getInt(16))
        assertEquals(1, le.getShort(20).toInt())
        assertEquals(2, le.getShort(22).toInt())
        assertEquals(48_000, le.getInt(24))
        assertEquals(192_000, le.getInt(28))
        assertEquals(4, le.getShort(32).toInt())
        assertEquals(16, le.getShort(34).toInt())
    }

    @Test fun endlessStreamUsesMaximumLengths() {
        assertEquals(-1, le.getInt(4))
        assertEquals(-1, le.getInt(40))
    }
}
