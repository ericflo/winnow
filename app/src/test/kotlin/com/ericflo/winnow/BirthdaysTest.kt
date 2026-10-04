package com.ericflo.winnow

import com.ericflo.winnow.data.Birthdays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.MonthDay

class BirthdaysTest {
    @Test
    fun `reads the dates contact cards hold`() {
        assertEquals(MonthDay.of(7, 14), Birthdays.parse("1990-07-14"))
        assertEquals(MonthDay.of(7, 14), Birthdays.parse("--07-14"))
        assertEquals(MonthDay.of(7, 14), Birthdays.parse("1990-07-14T00:00:00Z"))
        assertEquals(MonthDay.of(2, 29), Birthdays.parse("--02-29"))
        assertEquals(MonthDay.of(12, 3), Birthdays.parse("12/3/1985"))
        assertNull(Birthdays.parse("next tuesday"))
        assertNull(Birthdays.parse("1990-13-40"))
    }
}
