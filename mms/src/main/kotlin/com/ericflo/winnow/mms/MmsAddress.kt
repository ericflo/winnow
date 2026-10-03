package com.ericflo.winnow.mms

/**
 * Converts between clean addresses ("+15551234567", "ann@example.com") and their wire form
 * ("+15551234567/TYPE=PLMN"). Phone numbers lose visual separators; emails and addresses with
 * another explicit type (e.g. "/TYPE=IPV4") pass through trimmed.
 */
object MmsAddress {
    private const val PLMN_SUFFIX = "/TYPE=PLMN"
    private val PHONE = Regex("""\+?[0-9*#()\s.-]+""")
    private val SEPARATORS = Regex("""[()\s.-]""")

    /** Strips "/TYPE=PLMN" and phone punctuation: "+1 (555) 123-4567/TYPE=PLMN" becomes "+15551234567". */
    fun normalize(address: String): String {
        val trimmed = address.trim()
        val bare = if (trimmed.endsWith(PLMN_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(PLMN_SUFFIX.length).trim()
        } else {
            trimmed
        }
        return if (isPhoneNumber(bare)) bare.replace(SEPARATORS, "") else bare
    }

    /** The wire form: a normalized phone number plus "/TYPE=PLMN"; anything else as normalized. */
    fun encode(address: String): String {
        val clean = normalize(address)
        return if (isPhoneNumber(clean)) clean + PLMN_SUFFIX else clean
    }

    private fun isPhoneNumber(address: String): Boolean = PHONE.matches(address) && address.any { it.isDigit() }
}
