package com.ericflo.winnow.classifier.message

/** Strips sensitive detail from a message body before it leaves the phone, keeping its shape. */
object Redactor {
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val URL = Regex("""\b((?:https?://)?(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,})(/\S*)?""")
    private val DIGIT_RUN = Regex("""\d{4,}""")

    fun redact(text: String, policy: RedactionPolicy): String {
        var out = text
        if (policy.maskEmails) out = EMAIL.replace(out, "[email]")
        if (policy.stripUrlPaths) {
            out = URL.replace(out) { m ->
                val path = m.groupValues[2]
                if (path.length > 1) "${m.groupValues[1]}/…" else m.value
            }
        }
        if (policy.maskDigitRuns) out = DIGIT_RUN.replace(out) { "#".repeat(it.value.length) }
        return out
    }
}
