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

    private fun seal(plain: ByteArray, writeInPieces: Boolean = false, finish: Boolean = true): ByteArray {
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
            if (finish) sealed.finish()
        }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, password: String = "correct horse"): ByteArray {
        val input = ByteArrayInputStream(file)
        val header = BackupCrypto.readHeader(input)
        val k = BackupCrypto.deriveKey(password.toCharArray(), header.params)
        return BackupCrypto.decrypting(input, header, k).readBytes()
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
    fun `the wrong password is told apart from damage anywhere`() {
        val sealed = seal(Random(1).nextBytes(100_000))
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { open(sealed, password = "wrong") }
        // A byte changed in the salt, the key check, the first segment or the last: damage, not a wrong password.
        for (at in listOf(BackupCrypto.HEAD_BYTES + 6, BackupCrypto.HEADER_BYTES - 40, BackupCrypto.HEADER_BYTES + 50, sealed.size - 100)) {
            val damaged = sealed.copyOf().also { it[at] = (it[at] + 1).toByte() }
            val thrown = assertThrows("byte $at", IOException::class.java) { open(damaged) }
            assertFalse("byte $at", thrown is BackupCrypto.WrongPasswordException)
        }
    }

    @Test
    fun `the key check answers before anything is read`() {
        val header = BackupCrypto.readHeader(ByteArrayInputStream(seal(byteArrayOf(1))))
        assertTrue(BackupCrypto.opens(header, key))
        assertFalse(BackupCrypto.opens(header, BackupCrypto.deriveKey("wrong".toCharArray(), header.params)))
        assertFalse(BackupCrypto.opens(header, BackupCrypto.deriveKey("correct horse".toCharArray(), BackupCrypto.newParams(iterations = 1_000))))
    }

    @Test
    fun `a stream closed without finishing doesn't open`() {
        val plain = Random(5).nextBytes(150_000)
        val unfinished = seal(plain, finish = false)
        assertThrows(IOException::class.java) { open(unfinished) }
    }

    @Test
    fun `a file cut short or added to doesn't open`() {
        val sealed = seal(Random(2).nextBytes(150_000))
        // Cut at a segment boundary: the last segment is missing, which the format notices.
        val firstSegmentEnd = BackupCrypto.HEADER_BYTES + 1 + 4 + 65_536 + 16
        assertThrows(IOException::class.java) { open(sealed.copyOf(firstSegmentEnd)) }
        val cut = assertThrows(IOException::class.java) { open(sealed.copyOf(sealed.size - 1)) }
        assertTrue(cut.message.orEmpty().contains("cut off"))
        assertThrows(IOException::class.java) { open(sealed.copyOf(BackupCrypto.HEADER_BYTES - 1)) }
        assertThrows(IOException::class.java) { open(sealed + byteArrayOf(0)) }
    }

    @Test
    fun `segments can't be moved between files sealed with the same key`() {
        val a = seal(Random(3).nextBytes(10))
        val b = seal(Random(4).nextBytes(10))
        val headerSize = BackupCrypto.HEADER_BYTES
        // a's header with b's (only) segment: b's prefix differs, so its nonce and header don't match.
        val spliced = a.copyOf(headerSize) + b.copyOfRange(headerSize, b.size)
        assertThrows(IOException::class.java) { open(spliced) }
    }
}
