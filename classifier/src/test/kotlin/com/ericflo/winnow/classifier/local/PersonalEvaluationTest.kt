package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalEvaluationTest {
    private val base = OnDeviceClassifier()
    private val model = LocalModel.bundled

    /** Corpus texts as the user's labels: each its own conversation unless [perConversation] > 1. */
    private fun labels(n: Int, perConversation: Int = 1): List<PersonalEvaluation.Label> =
        Corpus.load(File("training/corpus")).shuffled(kotlin.random.Random(11)).take(n).mapIndexedNotNull { i, t ->
            val c = base.correction(InboundMessage(t.sender, t.body), setOf(t.category)) ?: return@mapIndexedNotNull null
            PersonalEvaluation.Label(c.buckets, c.label, group = (i / perConversation).toLong())
        }

    @Test
    fun everyLabelIsScoredOnceAndTheSameWayEachTime() {
        val labels = labels(120, perConversation = 3)
        val first = PersonalEvaluation.crossValidate(model, labels)
        assertEquals(labels.size, first.size)
        val again = PersonalEvaluation.crossValidate(model, labels)
        assertEquals(first.map { it.label to it.probabilities.toList() }.sortedBy { it.toString() }, again.map { it.label to it.probabilities.toList() }.sortedBy { it.toString() })
    }

    @Test
    fun aLabelIsNeverScoredByAModelThatSawItsConversation() {
        // Two conversations whose texts are identical within each, labeled against the grain:
        // a model that saw a twin would get it "right"; one that didn't, can't.
        val promo = base.correction(InboundMessage("38411", "Brew Lab: Taco Tuesday! \$2 tacos tonight. Reply STOP to opt out"), setOf(Category.PERSONAL))!!
        val bank = base.correction(InboundMessage("+17025550161", "Chase: your account is locked. Verify at chase-secure-verify.com"), setOf(Category.PERSONAL))!!
        val twins = List(5) { PersonalEvaluation.Label(promo.buckets, promo.label, group = 1) } + List(5) { PersonalEvaluation.Label(bank.buckets, bank.label, group = 2) }
        val rows = PersonalEvaluation.crossValidate(model, twins, folds = 2)
        val personal = model.classes.indexOf(Category.PERSONAL.key)
        assertTrue("held-out twins aren't scored as personal", rows.none { it.predicted == personal })
    }

    @Test
    fun tooFewLabelsMeansNoCharts() {
        assertNull(PersonalEvaluation.metrics(model, labels(10), emptyList(), filterAt = 0.85))
        val oneKind = labels(200).filter { it.label == 0 }
        assertNull(PersonalEvaluation.metrics(model, oneKind, emptyList(), filterAt = 0.85))
    }

    @Test
    fun enoughLabelsGiveEveryChart() {
        val m = PersonalEvaluation.metrics(model, labels(150), emptyList(), filterAt = 0.85)!!
        assertEquals(150, m.examples)
        assertEquals(Category.entries.size, m.confusion.size)
        assertEquals(150, m.confusion.sumOf { it.sum() })
        assertTrue(m.unwanted.roc.isNotEmpty())
        assertTrue(m.calibration.sumOf { it.count } == 150)
    }

    // A pharmacy's pickup texts, which read as transactional, that the user calls reminders.
    private val pickup = base.correction(InboundMessage("+12395550160", "Your prescription 4471 is ready for pickup at the Main St pharmacy"), setOf(Category.REMINDER))!!
    private val reminder = model.classes.indexOf(Category.REMINDER.key)

    private fun wordsSay(buckets: IntArray): Int =
        LocalModel.softmax(model.scores(buckets, Adjustments.NONE), model.temperature.toDouble()).let { p -> p.indices.maxBy { p[it] } }

    @Test
    fun aServiceLabelOfATextBeingScoredIsLeftOutOfItsRefit() {
        val labels = labels(60, perConversation = 3).mapIndexed { i, l -> PersonalEvaluation.Label(l.buckets, l.label, l.group, key = "sms:$i") }
        val target = labels.indices.first { wordsSay(labels[it].buckets) == labels[it].label }
        val wrong = (labels[target].label + 1) % model.classes.size
        // The service, sure and wrong about that one text, counting fifty times over.
        val service = listOf(Correction(labels[target].buckets, wrong, 50.0))
        fun scored(keys: List<String?>) = PersonalEvaluation.crossValidateIndexed(model, labels, service, othersKeys = keys).first { it.first == target }.second
        assertEquals("trained on, it would decide", wrong, scored(emptyList()).predicted)
        assertEquals(labels[target].label, scored(listOf("sms:$target")).predicted)
    }

    @Test
    fun aSendersLabelsInOtherConversationsCountAsOnThePhoneAndItsOwnNever() {
        assertTrue("the words alone don't say reminder", wordsSay(pickup.buckets) != reminder)
        // The pharmacy's texts in two conversations (a group text, say), in different folds: other
        // texts of theirs in the first, so its words teach little about a pickup text.
        val hours = base.correction(InboundMessage("+12395550160", "Main St pharmacy hours this week: Mon-Fri 9-7, Sat 10-4"), setOf(Category.REMINDER))!!
        fun of(other: Long) = List(3) { PersonalEvaluation.Label(hours.buckets, reminder, group = 1, sender = "+12395550160") } +
            listOf(PersonalEvaluation.Label(pickup.buckets, reminder, group = other, sender = "(239) 555-0160"))
        val labels = (2L..50L).map(::of).first { l -> PersonalEvaluation.foldsOf(l, 2)!!.values.toSet().size == 2 }
        val words = PersonalEvaluation.crossValidateIndexed(model, labels, folds = 2)
        val followed = PersonalEvaluation.withSenders(model.classes, labels, words, strength = 1.0, folds = 2).toMap()
        // Held out, conversation 2's text has the other conversation's three labels to go by: decided.
        assertEquals(reminder, followed.getValue(3).predicted)
        assertTrue("at least as sure as 3 of 3 labels make it", followed.getValue(3).confidence >= 0.75 - 1e-9)
        // Conversation 1's texts have only one label of the sender from elsewhere: a nudge, not a decision.
        assertTrue((0..2).all { followed.getValue(it).probabilities[reminder] > words.toMap().getValue(it).probabilities[reminder] })
        // Someone the user texts with: their labels lean, never decide.
        val person = labels.map { PersonalEvaluation.Label(it.buckets, it.label, it.group, sender = it.sender, conversing = true) }
        val leaned = PersonalEvaluation.withSenders(model.classes, person, words, strength = 1.0, folds = 2).toMap()
        assertTrue(leaned.getValue(3).probabilities[reminder] < followed.getValue(3).probabilities[reminder])
        // At strength 0, the words alone.
        assertTrue(PersonalEvaluation.withSenders(model.classes, labels, words, strength = 0.0, folds = 2) === words)
    }

    @Test
    fun theNewestLabelsAreScoredByARefitOnTheOlderOnesWithAndWithoutTheSendersLabels() {
        val older = labels(40).mapIndexed { i, l -> PersonalEvaluation.Label(l.buckets, l.label, l.group, key = "sms:$i", at = i.toLong()) }
        // The pharmacy: three labeled before, ten after.
        val before = List(3) { PersonalEvaluation.Label(pickup.buckets, reminder, group = 100, key = "p$it", sender = "+12395550160", at = 50L + it) }
        val after = List(10) { PersonalEvaluation.Label(pickup.buckets, reminder, group = 100, key = "q$it", sender = "+12395550160", at = 100L + it) }
        val n = PersonalEvaluation.scoreNewest(model, older + before + after, strength = 1.0, share = 0.0, atLeast = 10)!!
        assertEquals(10, n.count)
        assertEquals("the sender's earlier labels decide every one", 10, n.withSenders)
        assertTrue("the words alone can't follow all of them", n.words <= n.withSenders)
        assertEquals(n.words, PersonalEvaluation.scoreNewest(model, older + before + after, strength = 0.0, share = 0.0, atLeast = 10)!!.withSenders)
        assertNull("too few to say", PersonalEvaluation.scoreNewest(model, older.take(15)))
    }
}
