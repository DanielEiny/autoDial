package com.autodial.core

/** Pure helpers for validating and comparing phone numbers. No Android dependencies. */
object PhoneNumbers {

    /** Digits compared from the end; 9 covers Israeli mobile numbers (05X-XXXXXXX vs +9725X-XXXXXXX). */
    const val SUFFIX_MATCH_DIGITS = 9

    private val ALLOWED_FORMATTING = setOf(' ', '-', '(', ')', '.', ' ')

    /** Shorter numbers are rejected; this also excludes emergency/short service codes (100, 101, 112, 911...). */
    const val MIN_DIGITS = 5

    sealed class Validation {
        data class Valid(val dialString: String) : Validation()
        data class Invalid(val reason: String) : Validation()
    }

    /**
     * Strips formatting characters and validates. Rejects MMI/USSD characters (*, #), pauses, letters,
     * and very short numbers.
     */
    fun validate(raw: String): Validation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("Number is empty")
        val sb = StringBuilder()
        trimmed.forEach { c ->
            when {
                c in '0'..'9' -> sb.append(c)
                c == '+' && sb.isEmpty() -> sb.append(c)
                c in ALLOWED_FORMATTING -> Unit
                c == '*' || c == '#' -> return Validation.Invalid("Codes with * or # are not allowed")
                else -> return Validation.Invalid("Invalid character '$c'")
            }
        }
        val dial = sb.toString()
        val digits = dial.removePrefix("+")
        if (digits.length < MIN_DIGITS) return Validation.Invalid("Number is too short")
        if (digits.length > 15) return Validation.Invalid("Number is too long")
        return Validation.Valid(dial)
    }

    fun digitsOnly(s: String?): String = s.orEmpty().filter { it in '0'..'9' }

    /**
     * True if two numbers refer to the same line. Compares the last [SUFFIX_MATCH_DIGITS] digits when both
     * are long enough, otherwise requires exact digit equality.
     */
    fun sameNumber(a: String?, b: String?): Boolean {
        val da = digitsOnly(a)
        val db = digitsOnly(b)
        if (da.isEmpty() || db.isEmpty()) return false
        return if (da.length >= SUFFIX_MATCH_DIGITS && db.length >= SUFFIX_MATCH_DIGITS) {
            da.takeLast(SUFFIX_MATCH_DIGITS) == db.takeLast(SUFFIX_MATCH_DIGITS)
        } else {
            da == db
        }
    }

    /** Masks a number for logs: keeps the first 3 and last 4 characters, e.g. "050•••4567". */
    fun mask(s: String?): String {
        val v = s.orEmpty()
        if (v.length <= 7) return v
        return v.take(3) + "•••" + v.takeLast(4)
    }
}
