package com.ericflo.winnow.classifier.local

import com.ericflo.winnow.classifier.message.InboundMessage
import java.io.File

/** Prints the bundled model's mistakes on the evaluation set: a dev aid, run by `evalMistakes`. */
fun main() {
    val classifier = OnDeviceClassifier()
    val items = LocalModelBuild.loadEval(File("training/eval.tsv"))
    val wrong = items.map { it to classifier.classify(InboundMessage(it.sender, it.body)) }.filter { (t, p) -> t.category != p.category }
    println("${items.size - wrong.size} of ${items.size} right")
    wrong.forEach { (t, p) -> println("  ${t.category.key} → ${p.category.key} (${"%.0f".format(p.confidence * 100)}%): ${t.body.take(90)}") }
}
