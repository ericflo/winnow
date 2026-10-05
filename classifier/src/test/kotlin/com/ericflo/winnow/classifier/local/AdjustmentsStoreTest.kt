package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class AdjustmentsStoreTest {
    @Test
    fun whatWasLearnedReadsBackToTheSameGuesses() {
        val base = OnDeviceClassifier()
        val texts = listOf(
            "Your landlord here: please test the heater before Friday" to Category.REMINDER,
            "Practice moved to 6pm at the rec center" to Category.REMINDER,
            "Old Navy: 40% off everything today" to Category.SPAM,
        )
        val taught = base.learn(texts.map { (body, c) -> base.correction(InboundMessage("+12065550123", body), setOf(c))!! })
        val bytes = ByteArrayOutputStream().also { taught.adjustments.writeTo(DataOutputStream(it)) }.toByteArray()
        val read = base.withAdjustments(Adjustments.readFrom(DataInputStream(ByteArrayInputStream(bytes))))
        assertEquals(taught.adjustments.size, read.adjustments.size)
        for (body in texts.map { it.first } + "Reminder: trash pickup moves to Tuesday" + "hey are you around later?") {
            val a = taught.classify(InboundMessage("+12065550123", body))
            val b = read.classify(InboundMessage("+12065550123", body))
            assertEquals(body, a.distribution, b.distribution)
        }
    }

    @Test
    fun nothingLearnedReadsBackAsNothing() {
        val bytes = ByteArrayOutputStream().also { Adjustments.NONE.writeTo(DataOutputStream(it)) }.toByteArray()
        assertEquals(0, Adjustments.readFrom(DataInputStream(ByteArrayInputStream(bytes))).size)
    }
}
