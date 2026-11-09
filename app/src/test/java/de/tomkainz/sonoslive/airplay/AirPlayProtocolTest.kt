package de.tomkainz.sonoslive.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AirPlayProtocolTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun plistSurvivesARoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val original = linkedMapOf(
            "name" to "Küche",
            "ascii" to "Sunus",
            "port" to 7000,
            "big" to 0x1_0000_0000L,
            "yes" to true,
            "no" to false,
            "real" to 1.5,
            "shk" to key,
            "streams" to listOf(linkedMapOf("type" to 96, "ct" to 2))
        )
        val read = Bplist.read(Bplist.write(original)) as Map<*, *>
        assertEquals("Küche", read["name"])
        assertEquals("Sunus", read["ascii"])
        assertEquals(7000L, read["port"])
        assertEquals(0x1_0000_0000L, read["big"])
        assertEquals(true, read["yes"])
        assertEquals(false, read["no"])
        assertEquals(1.5, read["real"])
        assertArrayEquals(key, read["shk"] as ByteArray)
        val stream = (read["streams"] as List<*>).first() as Map<*, *>
        assertEquals(96L, stream["type"])
    }

    @Test
    fun plistMatchesApplesFormatByteForByte() {
        // {"a": 1}, written by Python's plistlib in the same layout: all sizes one byte.
        val expected = "62706c6973743030" + "d1" + "01" + "02" + "5161" + "1001" +
            "08" + "0b" + "0d" + // offsets
            "000000000000" + "01" + "01" + "0000000000000003" + "0000000000000000" + "000000000000000f"
        assertEquals(expected, Bplist.write(linkedMapOf("a" to 1)).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun tlvSplitsLongValuesAndJoinsThem() {
        val long = ByteArray(384) { it.toByte() }
        val encoded = Tlv8.write(listOf(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to long))
        assertEquals(3 + 2 + 255 + 2 + 129, encoded.size)
        val read = Tlv8.read(encoded)
        assertArrayEquals(long, read[Tlv8.PUBLIC_KEY])
        assertArrayEquals(byteArrayOf(3), read[Tlv8.STATE])
    }

    /** The server's side of SRP-6a, written from the RFC, so both ends have to agree. */
    @Test
    fun srpAgreesWithAServerWorkingFromTheVerifier() {
        val user = "Pair-Setup".toByteArray()
        val password = "3939".toByteArray()
        val salt = hex("beb25379d1a8581eb5a727673a2441ee")
        val n = SrpClient.N
        val g = SrpClient.G
        val pad = n.bitLength() / 8
        fun padded(v: BigInteger) = SrpClient.minimal(v).let { ByteArray(pad - it.size) + it }

        val x = BigInteger(1, SrpClient.sha512(salt, SrpClient.sha512(user, ":".toByteArray(), password)))
        val verifier = g.modPow(x, n)
        val k = BigInteger(1, SrpClient.sha512(SrpClient.minimal(n), padded(g)))
        val b = BigInteger("e487cb59d31ac550471e81f00f6928e01dda08e974a004f49e61f5d105284d20", 16)
        val serverPublic = k.multiply(verifier).add(g.modPow(b, n)).mod(n)

        val client = SrpClient(user, password)
        val proof = client.respond(salt, SrpClient.minimal(serverPublic))

        val a = client.publicKey
        val u = BigInteger(1, SrpClient.sha512(padded(a), padded(serverPublic)))
        val shared = a.multiply(verifier.modPow(u, n)).modPow(b, n)
        val serverKey = SrpClient.sha512(SrpClient.minimal(shared))
        assertArrayEquals(serverKey, client.sessionKey)

        val hn = SrpClient.sha512(SrpClient.minimal(n))
        val hg = SrpClient.sha512(SrpClient.minimal(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val expected = SrpClient.sha512(
            xor, SrpClient.sha512(user), salt, SrpClient.minimal(a), SrpClient.minimal(serverPublic), serverKey
        )
        assertArrayEquals(expected, proof)
        assertTrue(client.verifyServer(SrpClient.sha512(SrpClient.minimal(a), expected, serverKey)))
        assertFalse(client.verifyServer(ByteArray(64)))
    }

    @Test
    fun hkdfMatchesOpenSsl() {
        // openssl kdf -keylen 32 -kdfopt digest:SHA512 -kdfopt hexkey:07..07 -kdfopt salt:Control-Salt
        //   -kdfopt info:Control-Write-Encryption-Key HKDF
        val key = Hkdf.derive("Control-Salt", "Control-Write-Encryption-Key", ByteArray(64) { 7 })
        assertEquals("06324b0276c3d0ed1581565fbc3a9735e1264aef4f907476788273add656b8a4", key.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun plistWrittenByPythonIsRead() {
        // plistlib.dumps({"streams": [{"type": 96, "shk": bytes(range(32)), "name": "Küche"}], "yes": True, "big": 2**32})
        val data = hex(
            "62706c6973743030d301020304050d536269675773747265616d7353796573130000000100000000a106d30708090a0b0c" +
                "546e616d655373686b547479706565004b00fc0063006800654f1020000102030405060708090a0b0c0d0e0f1011" +
                "12131415161718191a1b1c1d1e1f106009080f131b1f282a31363a3f4a6d6f0000000000000101000000000000000e" +
                "00000000000000000000000000000070"
        )
        val read = Bplist.read(data) as Map<*, *>
        assertEquals(0x1_0000_0000L, read["big"])
        assertEquals(true, read["yes"])
        val stream = (read["streams"] as List<*>).single() as Map<*, *>
        assertEquals("Küche", stream["name"])
        assertEquals(96L, stream["type"])
        assertArrayEquals(ByteArray(32) { it.toByte() }, stream["shk"] as ByteArray)
    }

    @Test
    fun cipherMatchesRfc8439() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()
        val jca = runCatching { javax.crypto.Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { javax.crypto.Cipher.getInstance("ChaCha20-Poly1305") }
        jca.init(
            javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(hex("070000004041424344454647"))
        )
        jca.updateAAD(aad)
        val rfc = jca.doFinal(plain)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", rfc.takeLast(16).joinToString("") { "%02x".format(it) })
        // AirPlay's nonce has four zero bytes in front, so that vector cannot go through its class.
        val sealed = AirPlayCipher(key).encrypt(0x4746454443424140L, plain, 0, plain.size, aad)
        assertEquals(plain.size + 16, sealed.size)
        assertArrayEquals(plain, AirPlayCipher(key).decrypt(0x4746454443424140L, sealed, aad))
        val bad = sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(runCatching { AirPlayCipher(key).decrypt(0x4746454443424140L, bad, aad) }.isFailure)
    }

    @Test
    fun encryptedBlocksRoundTripAcrossTheLimit() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val message = ByteArray(2500) { (it % 251).toByte() }
        val sealed = HapStream(ByteArrayInputStream(ByteArray(0)), a, b).seal(message)
        assertEquals(message.size + 3 * (2 + 16), sealed.size)
        val reader = HapStream(ByteArrayInputStream(sealed), b, a)
        val read = ByteArray(message.size) { reader.read().toByte() }
        assertArrayEquals(message, read)
    }

    @Test
    fun alacFrameHasTheRightLayoutAndCarriesTheSamples() {
        val pcm = ShortArray(Alac.FRAMES * 2) { (it * 37 - 12000).toShort() }
        val out = ByteArray(Alac.SIZE)
        Alac.encode(pcm, 0, out)
        // Read it back the way a decoder does: 23 header bits, samples, 3 end bits.
        var bit = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((out[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
                bit++
            }
            return v
        }
        assertEquals("stereo element", 1, bits(3))
        assertEquals("instance", 0, bits(4))
        assertEquals("unused", 0, bits(12))
        assertEquals("no size in the frame", 0, bits(1))
        assertEquals("no extra bits", 0, bits(2))
        assertEquals("not compressed", 1, bits(1))
        for (s in pcm) assertEquals(s, bits(16).toShort())
        assertEquals("end tag", 7, bits(3))
        assertTrue(bit <= Alac.SIZE * 8)
    }

    private fun sine(frequency: Double, frames: Int, rate: Int = 48000, amplitude: Double = 16000.0) =
        ShortArray(frames * 2) { i ->
            (amplitude * sin(2 * PI * frequency * (i / 2) / rate)).toInt().toShort()
        }

    private fun resample(input: ShortArray): ShortArray {
        val r = Resampler()
        val out = ShortArray(input.size + 64)
        val buffer = ShortArray(2048)
        val room = ShortArray(Resampler.outputRoom(buffer.size))
        var at = 0
        var written = 0
        // In chunks of different sizes, as capture delivers them.
        var step = 192
        while (at < input.size) {
            val n = minOf(step, input.size - at)
            input.copyInto(buffer, 0, at, at + n)
            val made = r.process(buffer, n, room)
            room.copyInto(out, written, 0, made)
            written += made
            at += n
            step = if (step == 192) 1024 else 192
        }
        return out.copyOf(written)
    }

    private fun rms(s: ShortArray, skip: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in skip until s.size step 2) {
            sum += s[i].toDouble() * s[i]
            n++
        }
        return sqrt(sum / n)
    }

    @Test
    fun resamplerKeepsTheRateAndTheLevel() {
        val out = resample(sine(1000.0, 48000))
        assertTrue("got ${out.size / 2} frames", abs(out.size / 2 - 44100) <= 2)
        val db = 20 * log10(rms(out, 2000) / (16000.0 / sqrt(2.0)))
        assertTrue("level off by $db dB", abs(db) < 0.1)
        // The tone stays a tone at the same frequency: count the rising zero crossings.
        var crossings = 0
        for (i in 4000 until out.size - 2 step 2) if (out[i] < 0 && out[i + 2] >= 0) crossings++
        val seconds = (out.size - 4002) / 2 / 44100.0
        assertEquals(1000.0, crossings / seconds, 3.0)
    }

    @Test
    fun resamplerRejectsWhatWouldFoldBack() {
        val db = 20 * log10(rms(resample(sine(23500.0, 48000)), 2000).coerceAtLeast(0.01) / (16000.0 / sqrt(2.0)))
        assertTrue("only $db dB down", db < -60)
        val kept = 20 * log10(rms(resample(sine(18000.0, 48000)), 2000) / (16000.0 / sqrt(2.0)))
        assertTrue("18 kHz at $kept dB", abs(kept) < 0.5)
    }

    @Test
    fun ringDropsTheOldestWhenFull() {
        val ring = AudioRing(4)
        ring.write(shortArrayOf(1, 1, 2, 2, 3, 3), 6)
        ring.write(shortArrayOf(4, 4, 5, 5), 4)
        assertEquals(4, ring.frames())
        val out = ShortArray(4)
        assertTrue(ring.read(out, 2))
        assertArrayEquals(shortArrayOf(2, 2, 3, 3), out)
        assertFalse(ring.read(ShortArray(8), 4))
        ring.trimTo(1)
        assertEquals(1, ring.frames())
        assertTrue(ring.read(out, 1))
        assertEquals(5.toShort(), out[0])
    }

    @Test
    fun rtpPacketsAreBuiltAsTheSpeakerExpects() {
        val payload = ByteArray(16) { it.toByte() }
        val plain = AirPlayStreamer.rtp(true, 0x1234, 0xAABBCCDDL, 0x01020304, payload, null, 0)
        assertEquals("80e01234aabbccdd01020304", plain.take(12).joinToString("") { "%02x".format(it) })
        assertEquals(12 + 16, plain.size)
        assertEquals(0x1234, AirPlayStreamer.sequenceOf(plain))

        val key = ByteArray(32) { 9 }
        val sealed = AirPlayStreamer.rtp(false, 1, 2, 3, payload, AirPlayCipher(key), 5)
        assertEquals("80", "%02x".format(sealed[0]))
        assertEquals("60", "%02x".format(sealed[1]))
        assertEquals(12 + 16 + 16 + 8, sealed.size)
        // The last eight bytes are the nonce; time and source are authenticated.
        assertEquals("0500000000000000", sealed.takeLast(8).joinToString("") { "%02x".format(it) })
        val back = AirPlayCipher(key).decrypt(5, sealed.copyOfRange(12, sealed.size - 8), sealed.copyOfRange(4, 12))
        assertArrayEquals(payload, back)
    }

    @Test
    fun syncAndTimeAnswersHaveTheirFormat() {
        val s = AirPlayStreamer.sync(true, 100000, 0x0102030405060708L)
        assertEquals(20, s.size)
        assertEquals("90d40007", s.take(4).joinToString("") { "%02x".format(it) })
        val request = ByteArray(32).also {
            it[1] = 0xD2.toByte()
            for (i in 24 until 32) it[i] = (i - 23).toByte()
        }
        val answer = AirPlayStreamer.timingAnswer(request, 0x1112131415161718L)
        assertEquals(32, answer.size)
        assertEquals("80d30007", answer.take(4).joinToString("") { "%02x".format(it) })
        assertEquals("0102030405060708", answer.slice(8 until 16).joinToString("") { "%02x".format(it) })
        assertEquals("1112131415161718", answer.slice(16 until 24).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theClockFollowsTheSamples() {
        val start = 5000L shl 32
        assertEquals(start, AirPlayStreamer.ntpAt(start, 1000, 1000))
        assertEquals(start + (1L shl 32), AirPlayStreamer.ntpAt(start, 1000, 1000 + 44100))
        assertEquals(start + (1L shl 31), AirPlayStreamer.ntpAt(start, 1000, 1000 + 22050))
        // Across the point where the 32-bit timestamp wraps around.
        assertEquals(start + (2L shl 32), AirPlayStreamer.ntpAt(start, 0xFFFFFFFFL - 100, (0xFFFFFFFFL - 100 + 88200) and 0xFFFFFFFFL))
    }

    @Test
    fun featuresGiveTheWayIn() {
        val bits = AirPlayFeatures.parseBits("0x4A7FCA00,0x1C340")
        assertEquals((0x1C340L shl 32) or 0x4A7FCA00L, bits)
        assertTrue(AirPlayFeatures(bits, false).transientPairing)
        // /info gives one number, which may use the top bit.
        assertEquals(bits, AirPlayFeatures.parseBits("0x" + java.lang.Long.toHexString(bits)))
        assertEquals(-2L, AirPlayFeatures.parseBits("0xfffffffffffffffe"))
        assertEquals(AirPlayMode.AIRPLAY_2, AirPlayFeatures(bits, false).modes().first())
        assertEquals(AirPlayMode.CLASSIC, AirPlayFeatures(0x77L, false).modes().first())
        assertEquals(49152, AirPlayFeatures.fromDetails(AirPlayFeatures.toDetails("0x1", false, 49152)).classicPort)
        assertEquals(null, AirPlayFeatures.fromDetails("0x1;0").classicPort)
        val back = AirPlayFeatures.fromDetails(AirPlayFeatures.toDetails("0x4A7FCA00,0x1C340", true))
        assertEquals(bits, back.bits)
        assertTrue(back.passwordRequired)
    }

    @Test
    fun volumeMapsToDecibelsAndBack() {
        assertEquals(-144.0, AirPlaySession.percentToDb(0), 0.0)
        assertEquals(0.0, AirPlaySession.percentToDb(100), 0.0)
        assertEquals(-15.0, AirPlaySession.percentToDb(50), 0.0)
        for (p in listOf(1, 25, 50, 77, 100)) assertEquals(p, AirPlaySession.dbToPercent(AirPlaySession.percentToDb(p)))
        assertEquals(0, AirPlaySession.dbToPercent(-144.0))
    }
}
