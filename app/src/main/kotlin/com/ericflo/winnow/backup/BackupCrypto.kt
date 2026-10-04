package com.ericflo.winnow.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
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
 *     | key check (16) | SHA-256 of all the above (32)
 *     then per segment: last (1 byte, 0 or 1) | length (int) | ciphertext with its 16-byte tag
 *
 * The key is PBKDF2-HMAC-SHA256 of the password and salt. The key check is a GCM tag over
 * nothing, under a nonce no segment uses: it says whether a key is the right one before any
 * segment is read. The hash, which needs no key, catches a damaged header. So a wrong password
 * is told apart from damage anywhere in the file.
 */
object BackupCrypto {
    private val MAGIC = "WNWNENC1".toByteArray(Charsets.US_ASCII)
    const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val HASH_BYTES = 32
    /** A segment's plaintext: small enough to hold, big enough that the per-segment cost is noise. */
    private const val SEGMENT = 64 * 1024
    /** PBKDF2 rounds: about a second on a phone, to slow guessing at a stolen file. */
    const val ITERATIONS = 310_000
    /** The key check's nonce: a counter segments never reach, with a flag they never use. */
    private const val CHECK_COUNTER = -1
    private const val CHECK_FLAG: Byte = 2

    /** What a protected file's key comes from: [salt] and [iterations] are in its header. */
    class KeyParams(val salt: ByteArray, val iterations: Int) {
        fun sameAs(other: KeyParams): Boolean = iterations == other.iterations && salt.contentEquals(other.salt)
    }

    /** A key made from a password, with what it was made from. */
    class Key(val secret: SecretKey, val params: KeyParams)

    /** A protected file's header, read and checked by [readHeader]. */
    class Header internal constructor(val params: KeyParams, internal val prefix: ByteArray, internal val check: ByteArray) {
        /** What every segment and the key check authenticate: the header up to the key check. */
        internal val bytes: ByteArray get() = headerBytes(params, prefix)
    }

    fun deriveKey(password: CharArray, params: KeyParams): Key {
        val spec = PBEKeySpec(password, params.salt, params.iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return Key(SecretKeySpec(bytes, "AES"), params)
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    fun newParams(iterations: Int = ITERATIONS): KeyParams = KeyParams(ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes), iterations)

    /** Whether [head] (a file's first bytes) starts like a protected backup. */
    fun isProtected(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** How many bytes [isProtected] needs. */
    val HEAD_BYTES: Int get() = MAGIC.size

    /** How many bytes come before the first segment. */
    val HEADER_BYTES: Int get() = MAGIC.size + 4 + SALT_BYTES + PREFIX_BYTES + TAG_BYTES + HASH_BYTES

    /**
     * Reads a protected file's header, leaving [input] at its first segment. Fails with an
     * IOException if it isn't one, or it's damaged or cut off.
     */
    fun readHeader(input: InputStream): Header {
        val all = ByteArray(HEADER_BYTES)
        try {
            DataInputStream(input).readFully(all)
        } catch (_: EOFException) {
            throw IOException("This backup is cut off")
        }
        if (!all.copyOf(MAGIC.size).contentEquals(MAGIC)) throw IOException("Not a password-protected Winnow backup")
        val hashed = HEADER_BYTES - HASH_BYTES
        if (!MessageDigest.isEqual(sha256(all, hashed), all.copyOfRange(hashed, HEADER_BYTES))) throw IOException("This backup is damaged")
        val buffer = ByteBuffer.wrap(all, MAGIC.size, hashed - MAGIC.size)
        val iterations = buffer.int
        if (iterations !in 1..10_000_000) throw IOException("This backup is damaged")
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val prefix = ByteArray(PREFIX_BYTES).also(buffer::get)
        val check = ByteArray(TAG_BYTES).also(buffer::get)
        return Header(KeyParams(salt, iterations), prefix, check)
    }

    /** Whether [key] is the one [header]'s file was sealed with: the password's right. */
    fun opens(header: Header, key: Key): Boolean =
        key.params.sameAs(header.params) && MessageDigest.isEqual(checkTag(key.secret, header.bytes, header.prefix), header.check)

    /**
     * Writes a protected file to [output]: what's written to the stream returned is sealed with
     * [key]. Only [SealedOutput.finish] ends the file; closing the stream without it leaves one
     * that won't open, so a backup that fails partway can't pass for a whole one.
     */
    fun encrypting(output: OutputStream, key: Key): SealedOutput {
        val prefix = ByteArray(PREFIX_BYTES).also(SecureRandom()::nextBytes)
        val header = headerBytes(key.params, prefix)
        val unhashed = header + checkTag(key.secret, header, prefix)
        output.write(unhashed)
        output.write(sha256(unhashed, unhashed.size))
        return SealedOutput(output, key.secret, header, prefix)
    }

    /**
     * Opens a protected file whose [header] has been read already: reads what [encrypting]
     * wrote, failing with [WrongPasswordException] for the wrong key, before anything is read,
     * and an IOException for a damaged or cut-off file.
     */
    fun decrypting(input: InputStream, header: Header, key: Key): InputStream {
        if (!opens(header, key)) throw WrongPasswordException()
        return OpeningStream(input, key.secret, header.bytes, header.prefix)
    }

    /** The wrong password (or a file sealed with another key). */
    class WrongPasswordException : IOException("That password doesn't open this backup")

    private fun headerBytes(params: KeyParams, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + PREFIX_BYTES).put(MAGIC).putInt(params.iterations).put(params.salt).put(prefix).array()

    private fun nonce(prefix: ByteArray, counter: Int, flag: Byte): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(flag).array()

    private fun checkTag(key: SecretKey, header: ByteArray, prefix: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, CHECK_COUNTER, CHECK_FLAG)))
        cipher.updateAAD(header)
        return cipher.doFinal()
    }

    private fun sha256(bytes: ByteArray, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest()

    /** What [encrypting] returns: a stream to write the plaintext to, ended by [finish]. */
    class SealedOutput internal constructor(out: OutputStream, private val key: SecretKey, private val header: ByteArray, private val prefix: ByteArray) : FilterOutputStream(out) {
        private val buffer = ByteArray(SEGMENT)
        private var filled = 0
        private var counter = 0
        private var finished = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "Already finished" }
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

        /** Seals what's left as the last segment: the file is whole. The stream stays open. */
        fun finish() {
            if (finished) return
            seal(last = true)
            finished = true
            out.flush()
        }

        private fun seal(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, if (last) 1 else 0)))
            cipher.updateAAD(header)
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.write(if (last) 1 else 0)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            counter++
            filled = 0
        }

        override fun flush() = out.flush()

        /** Closes the file, ended or not (see [finish]). */
        override fun close() {
            buffer.fill(0)
            out.close()
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
                val (last, sealed) = try {
                    val flag = data.readUnsignedByte()
                    if (flag > 1) throw IOException("This backup is damaged")
                    val size = data.readInt()
                    if (size !in TAG_BYTES..SEGMENT + TAG_BYTES) throw IOException("This backup is damaged")
                    (flag == 1) to ByteArray(size).also(data::readFully)
                } catch (_: EOFException) {
                    throw IOException("This backup is cut off")
                }
                plain = try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, if (last) 1 else 0)))
                    cipher.updateAAD(header)
                    cipher.doFinal(sealed)
                } catch (e: GeneralSecurityException) {
                    // The key check passed, so the key is right: this is damage or tampering.
                    throw IOException("This backup is damaged", e)
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
