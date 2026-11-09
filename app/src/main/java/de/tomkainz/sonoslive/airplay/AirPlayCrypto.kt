package de.tomkainz.sonoslive.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pairing messages: type, length, value; values over 255 bytes come in pieces. */
object Tlv8 {
    const val METHOD = 0x00
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val STATE = 0x06
    const val ERROR = 0x07
    const val FLAGS = 0x13

    fun write(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var at = 0
            do {
                val n = minOf(255, value.size - at)
                out.write(type)
                out.write(n)
                out.write(value, at, n)
                at += n
            } while (at < value.size)
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val map = LinkedHashMap<Int, ByteArray>()
        var at = 0
        while (at + 2 <= data.size) {
            val type = data[at].toInt() and 0xFF
            val n = data[at + 1].toInt() and 0xFF
            if (at + 2 + n > data.size) throw IOException("Pairing message cut off")
            val value = data.copyOfRange(at + 2, at + 2 + n)
            map[type] = map[type]?.plus(value) ?: value
            at += 2 + n
        }
        return map
    }
}

/**
 * The client side of SRP-6a, 3072 bits with SHA-512, as AirPlay's pairing uses it.
 * "Transient" pairing runs it with the fixed secret 3939 and needs nothing else.
 */
class SrpClient(
    private val user: ByteArray,
    private val password: ByteArray,
    private val secret: BigInteger = BigInteger(512, SecureRandom())
) {
    val publicKey: BigInteger = G.modPow(secret, N)

    /** The common secret, once [respond] has run. */
    lateinit var sessionKey: ByteArray
        private set
    private lateinit var proof: ByteArray

    /** Returns the proof that this side knows the password. */
    fun respond(salt: ByteArray, serverKey: ByteArray): ByteArray {
        val b = BigInteger(1, serverKey)
        if (b.mod(N) == BigInteger.ZERO) throw IOException("Bad SRP key from the speaker")
        val a = publicKey
        val u = BigInteger(1, sha512(pad(a), pad(b)))
        val k = BigInteger(1, sha512(minimal(N), pad(G)))
        val x = BigInteger(1, sha512(salt, sha512(user, ":".toByteArray(), password)))
        val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val shared = base.modPow(secret.add(u.multiply(x)), N)
        sessionKey = sha512(minimal(shared))
        val hn = sha512(minimal(N))
        val hg = sha512(minimal(G))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        proof = sha512(xor, sha512(user), salt, minimal(a), minimal(b), sessionKey)
        return proof
    }

    fun verifyServer(serverProof: ByteArray): Boolean =
        MessageDigest.isEqual(serverProof, sha512(minimal(publicKey), proof, sessionKey))

    companion object {
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
                "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
                "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
                "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16
        )
        val G: BigInteger = BigInteger.valueOf(5)
        private val PAD = N.bitLength() / 8

        fun minimal(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        private fun pad(v: BigInteger): ByteArray {
            val m = minimal(v)
            return ByteArray(PAD - m.size) + m
        }

        fun sha512(vararg parts: ByteArray): ByteArray {
            val d = MessageDigest.getInstance("SHA-512")
            parts.forEach { d.update(it) }
            return d.digest()
        }
    }
}

object Hkdf {
    /** HKDF with SHA-512, 32 bytes out: all that AirPlay's keys need. */
    fun derive(salt: String, info: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA512"))
        val prk = mac.doFinal(key)
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        mac.update(info.toByteArray())
        mac.update(1)
        return mac.doFinal().copyOf(32)
    }
}

/** ChaCha20-Poly1305 with AirPlay's nonce: four zero bytes and a little-endian counter. */
class AirPlayCipher(key: ByteArray) {
    private val secret = SecretKeySpec(key, "ChaCha20")
    private val cipher = instance()

    fun encrypt(counter: Long, plain: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(nonce(counter)))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain, offset, length)
    }

    /** Throws if the data was tampered with or the key is wrong. */
    fun decrypt(counter: Long, data: ByteArray, aad: ByteArray): ByteArray {
        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(nonce(counter)))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    companion object {
        fun nonce(counter: Long) = ByteArray(12).also { n ->
            for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        }

        /** Android and the desktop JDK name the same cipher differently. */
        private fun instance(): Cipher = runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }
    }
}
