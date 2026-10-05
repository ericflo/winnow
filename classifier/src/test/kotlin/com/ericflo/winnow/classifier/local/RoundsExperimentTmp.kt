package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.InboundMessage
import org.junit.Test
import java.io.File

class RoundsExperimentTmp {
    @Test
    fun simulate() {
        val all = Corpus.load(File("training/corpus"))
        val (trainSet, unseen) = Corpus.split(all, 0.4, seed = 3)
        val (pool0, test) = Corpus.split(unseen, 0.4, seed = 5)
        val trainer = LocalModelTrainer(Corpus.classes)
        val base = OnDeviceClassifier(trainer.train(trainSet.map { it.example(Corpus.classes) }))
        fun msg(t: LabeledText) = InboundMessage(t.sender, t.body)
        println("EXP train=${trainSet.size} pool=${pool0.size} base pool acc=${"%.1f".format(100.0 * pool0.count { base.classify(msg(it)).category == it.category } / pool0.size)}%")
        for (strategy in listOf("personalizer (app today)", "full refit on labels")) {
            val pool = pool0.toMutableList()
            val labeled = mutableListOf<LabeledText>()
            val corrections = mutableListOf<Correction>()
            val line = StringBuilder("EXP $strategy:")
            repeat(10) { round ->
                val current = if (strategy.startsWith("personalizer")) base.learn(corrections)
                else OnDeviceClassifier(trainer.train((trainSet + labeled).map { it.example(Corpus.classes) }))
                val memorized = if (labeled.isEmpty()) "-" else "${100 * labeled.count { current.classify(msg(it)).category == it.category } / labeled.size}%"
                val scored = pool.map { it to current.classify(msg(it)) }
                val picks = scored.sortedBy { it.second.confidence }.take(20)
                val roundRight = picks.count { it.second.category == it.first.category }
                val testAcc = 100.0 * test.count { current.classify(msg(it)).category == it.category } / test.size
                line.append(" | R${round + 1} right ${roundRight}/20, fixed test set ${"%.1f".format(testAcc)}% (n=${test.size}), labeled-now-right $memorized")
                picks.forEach { (t, _) -> labeled += t; base.correction(msg(t), setOf(t.category))?.let { corrections += it } }
                pool.removeAll { t -> picks.any { it.first === t } }
            }
            println(line)
        }
    }
}
