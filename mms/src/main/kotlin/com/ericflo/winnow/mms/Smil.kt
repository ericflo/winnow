package com.ericflo.winnow.mms

/** Builds the minimal SMIL presentation that MMS clients expect in a multipart/related [SendReq]. */
object Smil {
    private const val SLIDE_MILLIS = 5000

    /**
     * An application/smil part (content-id "smil", name and location "smil.xml") laying [parts] out as
     * slides of at most one image or video, one text and one audio each, in order. Each part is
     * referenced by its content-location, else name, filename, or "cid:" content-id. Existing
     * SMIL parts are ignored. Put the result first in [SendReq.parts].
     *
     * @throws IllegalArgumentException if a part has nothing to reference it by.
     */
    fun forParts(parts: List<MmsPart>): MmsPart {
        val media = parts.filterNot { it.contentType.equals(ContentTypes.SMIL, ignoreCase = true) }
        val slides = mutableListOf<MutableMap<Slot, MmsPart>>()
        for (part in media) {
            val slot = Slot.of(part)
            val slide = slides.lastOrNull()
            if (slide == null || slot == Slot.OTHER || slot in slide || Slot.OTHER in slide) {
                slides += mutableMapOf(slot to part)
            } else {
                slide[slot] = part
            }
        }
        val hasVisual = media.any { Slot.of(it) == Slot.VISUAL }
        val hasText = media.any { Slot.of(it) == Slot.TEXT }
        val xml = buildString {
            append("""<smil><head><layout><root-layout width="100%" height="100%"/>""")
            if (hasVisual) append(region("Image", top = 0, height = if (hasText) 70 else 100, fit = "meet"))
            if (hasText) append(region("Text", top = if (hasVisual) 70 else 0, height = if (hasVisual) 30 else 100, fit = "scroll"))
            append("</layout></head><body>")
            for (slide in slides) {
                val timed = slide.values.any { it.isTimed() }
                append(if (timed) "<par>" else """<par dur="${SLIDE_MILLIS}ms">""")
                for (slot in Slot.entries) slide[slot]?.let { append(element(slot, it)) }
                append("</par>")
            }
            append("</body></smil>")
        }
        return MmsPart(
            contentType = ContentTypes.SMIL,
            data = xml.encodeToByteArray(),
            name = "smil.xml",
            contentId = "smil",
            contentLocation = "smil.xml",
        )
    }

    private enum class Slot {
        VISUAL, TEXT, AUDIO, OTHER;

        companion object {
            fun of(part: MmsPart): Slot = when {
                part.contentType.equals(ContentTypes.TEXT_PLAIN, ignoreCase = true) -> TEXT
                else -> when (part.contentType.substringBefore('/').lowercase()) {
                    "image", "video" -> VISUAL
                    "audio" -> AUDIO
                    else -> OTHER
                }
            }
        }
    }

    private fun MmsPart.isTimed() =
        contentType.startsWith("video/", ignoreCase = true) || contentType.startsWith("audio/", ignoreCase = true)

    private fun region(id: String, top: Int, height: Int, fit: String) =
        """<region id="$id" left="0" top="$top%" width="100%" height="$height%" fit="$fit"/>"""

    private fun element(slot: Slot, part: MmsPart): String {
        val src = escape(reference(part))
        return when (slot) {
            Slot.VISUAL -> if (part.isTimed()) """<video src="$src" region="Image"/>""" else """<img src="$src" region="Image"/>"""
            Slot.TEXT -> """<text src="$src" region="Text"/>"""
            Slot.AUDIO -> """<audio src="$src"/>"""
            Slot.OTHER -> """<ref src="$src"/>"""
        }
    }

    private fun reference(part: MmsPart): String =
        part.contentLocation ?: part.name ?: part.filename ?: part.contentId?.let { "cid:$it" }
            ?: throw IllegalArgumentException("a ${part.contentType} part has no location, name, filename or content-id")

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
