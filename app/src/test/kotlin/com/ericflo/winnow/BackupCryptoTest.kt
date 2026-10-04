package com.ericflo.winnow

import com.ericflo.winnow.backup.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class BackupCryptoTest {
    // Few rounds: the test is of the format, not of how slow guessing is.
    private val params = BackupCrypto.newParams(iterations = 1_000)
    private val key = BackupCrypto.deriveKey("correct horse".toCharArray(), params)

    private fun seal(plain: ByteArray, writeInPieces: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypting(out, key).use { sealed ->
            if (writeInPieces) {
                var i = 0
                while (i < plain.size) {
                    val n = minOf(777, plain.size - i)
                    sealed.write(plain, i, n)
                    i += n
                }
            } else {
                sealed.write(plain)
            }
        }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, password: String = "correct horse"): ByteArray {
        val input = ByteArrayInputStream(file)
        val read = BackupCrypto.readParams(input)
        val k = BackupCrypto.deriveKey(password.toCharArray(), read)
        return BackupCrypto.decrypting(input, k).readBytes()
    }

    @Test
    fun `round trips files of every size, across segment boundaries`() {
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 200_000)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, open(seal(plain)))
            assertArrayEquals("size $size in pieces", plain, open(seal(plain, writeInPieces = true)))
        }
    }

    @Test
    fun `is told apart from a plain zip`() {
        val sealed = seal(byteArrayOf(1, 2, 3))
        assertTrue(BackupCrypto.isProtected(sealed.copyOf(BackupCrypto.HEAD_BYTES)))
        assertFalse(BackupCrypto.isProtected(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0)))
    }

    @Test
    fun `the wrong password is told apart from damage`() {
        val sealed = seal(Random(1).nextBytes(100_000))
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { open(sealed, password = "wrong") }
        // A byte changed in the second segment: damage, not a wrong password.
        val damaged = sealed.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() }
        val thrown = assertThrows(IOException::class.java) { open(damaged) }
        assertFalse(thrown is BackupCrypto.WrongPasswordException)
    }

    @Test
    fun `a file cut short or added to doesn't open`() {
        val sealed = seal(Random(2).nextBytes(150_000))
        // Cut at a segment boundary: the last segment is missing, which the format notices.
        val firstSegmentEnd = BackupCrypto.HEAD_BYTES + 4 + BackupCrypto.SALT_BYTES + 7 + 1 + 4 + 65_536 + 16
        assertThrows(IOException::class.java) { open(sealed.copyOf(firstSegmentEnd)) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(sealed.size - 1)) }
        assertThrows(IOException::class.java) { open(sealed + byteArrayOf(0)) }
    }

    @Test
    fun `segments can't be moved between files sealed with the same key`() {
        val a = seal(Random(3).nextBytes(10))
        val b = seal(Random(4).nextBytes(10))
        val headerSize = BackupCrypto.HEAD_BYTES + 4 + BackupCrypto.SALT_BYTES + 7
        // a's header with b's (only) segment: b's prefix differs, so its nonce and header don't match.
        val spliced = a.copyOf(headerSize) + b.copyOfRange(headerSize, b.size)
        assertThrows(IOException::class.java) { open(spliced) }
    }
}
