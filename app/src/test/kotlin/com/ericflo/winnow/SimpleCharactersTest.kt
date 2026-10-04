package com.ericflo.winnow

import com.ericflo.winnow.data.SimpleCharacters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleCharactersTest {
    private val long = "We’re meeting at the lake house on Saturday — bring a jacket, it gets cold by the water. "

    @Test
    fun `smart punctuation in a long text becomes plain, saving texts`() {
        val text = long + "Can’t wait… “see you there”"
        assertEquals(2, SimpleCharacters.measure(text).segments)
        val simple = SimpleCharacters.forSms(text)
        assertEquals(
            "We're meeting at the lake house on Saturday - bring a jacket, it gets cold by the water. Can't wait... \"see you there\"",
            simple,
        )
        assertEquals(SimpleCharacters.Measure(1, true), SimpleCharacters.measure(simple))
    }

    @Test
    fun `a short text goes as typed, since it's one text either way`() {
        val text = "Can’t wait — see you at 6"
        assertEquals(text, SimpleCharacters.forSms(text))
    }

    @Test
    fun `an emoji keeps the long form, so nothing is changed`() {
        val text = long + "Can’t wait 🎉"
        assertEquals(text, SimpleCharacters.forSms(text))
    }

    @Test
    fun `accents the alphabet has stay, others lose their accent`() {
        assertEquals("José, Müller, Søren and Ñoño at the café", SimpleCharacters.simplify("José, Müller, Søren and Ñoño at the café"))
        assertEquals("Joao, Francois, Ines, Zoe, oeuvre, Lodz", SimpleCharacters.simplify("João, François, Inês, Zoë, œuvre, Łódź"))
    }

    @Test
    fun `characters with no stand-in stay`() {
        // Greek lowercase, Cyrillic and CJK have none, so the text stays in the long form, unchanged.
        val text = long + "Привет, мир. 你好"
        assertEquals(text, SimpleCharacters.forSms(text))
    }

    @Test
    fun `odd spaces and tabs become plain spaces`() {
        assertEquals("10 am - 2 pm", SimpleCharacters.simplify("10 am – 2 pm"))
        assertEquals("a b", SimpleCharacters.simplify("a\tb"))
    }

    @Test
    fun `plain text is left alone and simplifying twice changes nothing`() {
        val plain = "Plain ASCII, with {braces} and €5 — no wait"
        val once = SimpleCharacters.simplify(plain)
        assertEquals(once, SimpleCharacters.simplify(once))
        val gsm = long.replace('’', '\'').replace('—', '-')
        assertTrue(SimpleCharacters.measure(gsm).sevenBit)
        assertEquals(gsm, SimpleCharacters.forSms(gsm))
    }

    @Test
    fun `the count follows the GSM rules`() {
        assertEquals(SimpleCharacters.Measure(1, true), SimpleCharacters.measure("a".repeat(160)))
        assertEquals(SimpleCharacters.Measure(2, true), SimpleCharacters.measure("a".repeat(161)))
        assertEquals(SimpleCharacters.Measure(3, true), SimpleCharacters.measure("a".repeat(307)))
        // Extension characters take two places.
        assertEquals(SimpleCharacters.Measure(2, true), SimpleCharacters.measure("€".repeat(81)))
        assertEquals(SimpleCharacters.Measure(1, false), SimpleCharacters.measure("’".repeat(70)))
        assertEquals(SimpleCharacters.Measure(2, false), SimpleCharacters.measure("’".repeat(71)))
        assertFalse(SimpleCharacters.measure("`").sevenBit)
    }

    @Test
    fun `the phone's own count decides`() {
        // A carrier alphabet that already carries the text in 7 bits: nothing to save.
        val text = long + "Can’t wait"
        assertEquals(text, SimpleCharacters.forSms(text) { SimpleCharacters.Measure(1, true) })
    }
}
