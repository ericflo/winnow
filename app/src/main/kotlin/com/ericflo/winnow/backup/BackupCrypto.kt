package com.ericflo.winnow.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backups. Pure JVM, so it's unit-tested without Android.
 *
 * A protected file is a header, then the backup zip in segments, each sealed on its own with
 * AES-256-GCM: a whole backup can be hundreds of megabytes, and one GCM stream would have to be
 * held in memory to be checked. Each segment's nonce is the file's random prefix, its number and
 * whether it's the last, and the header is authenticated with every segment, so segments can't
 * be reordered, dropped, cut off at the end or moved between files without the read failing.
 *
 *     magic "WNWNENC1" | iterations (int) | salt (16) | nonce prefix (7)
 *     then per segment: last (1 byte, 0 or 1) | length (int) | ciphertext with its 16-byte tag
 *
 * The key is PBKDF2-HMAC-SHA256 of the password and salt.
 */
object BackupCrypto {
    private val MAGIC = "WNWNENC1".toByteArray(Charsets.US_ASCII)
    const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val TAG_BITS = 128
    /** A segment's plaintext: small enough to hold, big enough that the per-segment cost is noise. */
    private const val SEGMENT = 64 * 1024
    /** PBKDF2 rounds: about a second on a phone, to slow guessing at a stolen file. */
    const val ITERATIONS = 310_000

    /** What a protected file's key comes from: [salt] and [iterations] are in its header. */
    class KeyParams(val salt: ByteArray, val iterations: Int)

    /** A key made from a password, with what it was made from. */
    class Key(val secret: SecretKey, val params: KeyParams)

    fun deriveKey(password: CharArray, params: KeyParams): Key {
        val spec = PBEKeySpec(password, params.salt, params.iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Key(SecretKeySpec(bytes, "AES"), params)
        } finally {
            spec.clearPassword()
        }
    }

    fun newParams(iterations: Int = ITERATIONS): KeyParams = KeyParams(ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes), iterations)

    /** Whether [head] (a file's first bytes) starts like a protected backup. */
    fun isProtected(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** How many bytes [isProtected] needs. */
    val HEAD_BYTES: Int get() = MAGIC.size

    /** A protected file's key parameters, read from its header (the stream is left after them). */
    fun readParams(input: InputStream): KeyParams {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a password-protected Winnow backup")
        val iterations = data.readInt()
        if (iterations !in 1..10_000_000) throw IOException("This backup is damaged")
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        return KeyParams(salt, iterations)
    }

    /** Writes a protected file to [output]: what's written to the stream returned is sealed with [key]. */
    fun encrypting(output: OutputStream, key: Key): OutputStream {
        val prefix = ByteArray(PREFIX_BYTES).also(SecureRandom()::nextBytes)
        val header = header(key.params, prefix)
        output.write(header)
        return SealingStream(output, key.secret, header, prefix)
    }

    /**
     * Opens a protected file whose header has been read already (see [readParams]): reads what
     * [encrypting] wrote, failing with [WrongPasswordException] for the wrong key and an
     * IOException for a damaged or cut-off file.
     */
    fun decrypting(input: InputStream, key: Key): InputStream {
        val prefix = ByteArray(PREFIX_BYTES).also(DataInputStream(input)::readFully)
        return OpeningStream(input, key.secret, header(key.params, prefix), prefix)
    }

    /** The wrong password (or a file sealed with another key): the first segment doesn't open. */
    class WrongPasswordException : IOException("That password doesn't open this backup")

    private fun header(params: KeyParams, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + PREFIX_BYTES).put(MAGIC).putInt(params.iterations).put(params.salt).put(prefix).array()

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    private class SealingStream(out: OutputStream, private val key: SecretKey, private val header: ByteArray, private val prefix: ByteArray) : FilterOutputStream(out) {
        private val buffer = ByteArray(SEGMENT)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                // A full buffer goes out only once more is coming, so the last segment is never empty
                // unless the whole file is.
                if (filled == SEGMENT) seal(last = false)
                val n = minOf(left, SEGMENT - filled)
                System.arraycopy(b, at, buffer, filled, n)
                filled += n
                at += n
                left -= n
            }
        }

        private fun seal(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.write(if (last) 1 else 0)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            counter++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class OpeningStream(input: InputStream, private val key: SecretKey, private val header: ByteArray, private val prefix: ByteArray) : InputStream() {
        private val data = DataInputStream(input)
        private var plain = ByteArray(0)
        private var at = 0
        private var counter = 0
        private var done = false

        override fun read(): Int {
            if (!fill()) return -1
            return plain[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - at)
            System.arraycopy(plain, at, b, off, n)
            at += n
            return n
        }

        /** Something left to read: true, or the end of a whole, untampered file. */
        private fun fill(): Boolean {
            while (at >= plain.size) {
                if (done) return false
                val flag = try {
                    data.readUnsignedByte()
                } catch (_: EOFException) {
                    throw IOException("This backup is cut off")
                }
                if (flag > 1) throw IOException("This backup is damaged")
                val size = data.readInt()
                if (size !in 16..SEGMENT + 16) throw IOException("This backup is damaged")
                val sealed = ByteArray(size).also(data::readFully)
                val last = flag == 1
                plain = try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
                    cipher.updateAAD(header)
                    cipher.doFinal(sealed)
                } catch (e: GeneralSecurityException) {
                    // The first segment is where a wrong password shows; later, it's tampering.
                    throw if (counter == 0) WrongPasswordException() else IOException("This backup is damaged", e)
                }
                at = 0
                counter++
                if (last) {
                    done = true
                    if (data.read() != -1) throw IOException("This backup has something extra at its end")
                }
            }
            return true
        }

        override fun close() = data.close()
    }
}
