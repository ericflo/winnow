package com.ericflo.winnow.mms

/** Assembles a byte fixture from octets (Int), ASCII text (String) and nested ByteArrays. */
fun bytes(vararg items: Any): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (item in items) {
        when (item) {
            is Int -> {
                require(item in 0..0xFF) { "not an octet: $item" }
                out.write(item)
            }
            is String -> out.write(item.toByteArray(Charsets.US_ASCII))
            is ByteArray -> out.write(item)
            else -> error("unsupported fixture item: $item")
        }
    }
    return out.toByteArray()
}

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

fun ByteArray.indexOf(needle: ByteArray): Int =
    (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1
