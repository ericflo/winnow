package com.ericflo.winnow.classifier.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsCalculatorTest {
    private fun s(vararg pairs: Pair<Double, Boolean>) = pairs.toList()

    @Test
    fun `ROC AUC matches hand-computed values`() {
        assertEquals(1.0, MetricsCalculator.auc(s(0.9 to true, 0.8 to true, 0.2 to false, 0.1 to false)), 1e-12)
        assertEquals(0.0, MetricsCalculator.auc(s(0.9 to false, 0.8 to false, 0.2 to true, 0.1 to true)), 1e-12)
        // One of the four positive/negative pairs is out of order.
        assertEquals(0.75, MetricsCalculator.auc(s(0.9 to true, 0.8 to false, 0.7 to true, 0.6 to false)), 1e-12)
        // Ties count half.
        assertEquals(0.5, MetricsCalculator.auc(s(0.5 to true, 0.5 to false)), 1e-12)
    }

    @Test
    fun `average precision`() {
        assertEquals(1.0, MetricsCalculator.averagePrecision(s(0.9 to true, 0.8 to true, 0.1 to false)), 1e-12)
        // Positives found at ranks 1 and 3: (1/2)·1 + (1/2)·(2/3).
        assertEquals(0.5 + 1.0 / 3, MetricsCalculator.averagePrecision(s(0.9 to true, 0.8 to false, 0.7 to true)), 1e-12)
    }

    @Test
    fun `Cohen's kappa and MCC`() {
        // Observed agreement 0.7, chance agreement 0.5.
        val m = arrayOf(intArrayOf(20, 5), intArrayOf(10, 15))
        assertEquals(0.4, MetricsCalculator.kappa(m), 1e-12)
        // Binary MCC: (TP·TN − FP·FN) / sqrt((TP+FP)(TP+FN)(TN+FP)(TN+FN)), TN=20 FP=5 FN=10 TP=15.
        val expected = (15.0 * 20 - 5.0 * 10) / Math.sqrt(20.0 * 25 * 25 * 30)
        assertEquals(expected, MetricsCalculator.mcc(m), 1e-12)

        val perfect = arrayOf(intArrayOf(5, 0, 0), intArrayOf(0, 7, 0), intArrayOf(0, 0, 3))
        assertEquals(1.0, MetricsCalculator.kappa(perfect), 1e-12)
        assertEquals(1.0, MetricsCalculator.mcc(perfect), 1e-12)
        assertEquals(0.0, MetricsCalculator.mcc(arrayOf(intArrayOf(5, 5), intArrayOf(5, 5))), 1e-12)
    }

    @Test
    fun `calibration bins and summary metrics`() {
        val rows = listOf(
            Scored(0, doubleArrayOf(0.95, 0.05)),
            Scored(1, doubleArrayOf(0.1, 0.9)),
            Scored(1, doubleArrayOf(0.6, 0.4)),
            Scored(0, doubleArrayOf(0.8, 0.2)),
        )
        val m = MetricsCalculator.compute("t", "test", listOf("ham", "spam"), rows, unwanted = setOf(1), filterAt = 0.85)
        assertEquals(0.75, m.accuracy, 1e-12)
        assertEquals(4, m.calibration.sumOf { it.count })
        assertEquals(listOf(listOf(2, 0), listOf(1, 1)), m.confusion)
        assertEquals(1.0, m.unwanted.auc, 1e-12)
        // Winnow's rule flags only the confident spam prediction.
        assertEquals(0.5, m.unwanted.operatingPoint.recall, 1e-12)
        assertEquals(1.0, m.unwanted.operatingPoint.precision, 1e-12)
    }
}
