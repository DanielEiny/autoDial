package com.autodial.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    private fun valid(raw: String) = (PhoneNumbers.validate(raw) as PhoneNumbers.Validation.Valid).dialString

    @Test
    fun stripsFormatting() {
        assertEquals("0501234567", valid(" 050-123 4567 "))
        assertEquals("+972501234567", valid("+972 (50) 123-4567"))
    }

    @Test
    fun rejectsMmiAndLetters() {
        assertTrue(PhoneNumbers.validate("*21*0501234567#") is PhoneNumbers.Validation.Invalid)
        assertTrue(PhoneNumbers.validate("050123456a") is PhoneNumbers.Validation.Invalid)
        assertTrue(PhoneNumbers.validate("05012+34567") is PhoneNumbers.Validation.Invalid)
        assertTrue(PhoneNumbers.validate("0501234567,123") is PhoneNumbers.Validation.Invalid)
    }

    @Test
    fun rejectsEmptyShortAndEmergency() {
        for (n in listOf("", "  ", "100", "101", "112", "911", "1234")) {
            assertTrue(n, PhoneNumbers.validate(n) is PhoneNumbers.Validation.Invalid)
        }
    }

    @Test
    fun rejectsTooLong() {
        assertTrue(PhoneNumbers.validate("1234567890123456") is PhoneNumbers.Validation.Invalid)
    }

    @Test
    fun sameNumberMatchesAcrossFormats() {
        assertTrue(PhoneNumbers.sameNumber("+972501234567", "050-123-4567"))
        assertTrue(PhoneNumbers.sameNumber("0501234567", "0501234567"))
        assertFalse(PhoneNumbers.sameNumber("0501234567", "0501234568"))
        assertFalse(PhoneNumbers.sameNumber(null, "0501234567"))
        assertFalse(PhoneNumbers.sameNumber("", ""))
    }

    @Test
    fun shortNumbersNeedExactMatch() {
        assertTrue(PhoneNumbers.sameNumber("12345", "1-2345"))
        assertFalse(PhoneNumbers.sameNumber("12345", "912345"))
    }

    @Test
    fun maskHidesMiddle() {
        assertEquals("050•••4567", PhoneNumbers.mask("0501234567"))
        assertEquals("12345", PhoneNumbers.mask("12345"))
    }
}
