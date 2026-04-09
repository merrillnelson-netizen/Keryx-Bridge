package app.keryx.bridge.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Normalizes phone numbers to E.164 format using Google's libphonenumber.
 * Passes alphanumeric thread IDs (e.g., Google Messages thread IDs) through verbatim.
 */
object PhoneNormalizer {

    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()
    private val DEFAULT_REGION = "US"

    /**
     * Attempts to parse [raw] as a phone number and return it in E.164 format.
     * Returns [raw] unchanged if it cannot be parsed (e.g., alphanumeric thread IDs,
     * short codes, or already-normalized numbers that failed secondary validation).
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return raw

        // If the string contains letters (typical thread ID), pass through verbatim
        if (trimmed.any { it.isLetter() }) return trimmed

        return try {
            val number = phoneUtil.parse(trimmed, DEFAULT_REGION)
            if (phoneUtil.isValidNumber(number)) {
                phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                trimmed
            }
        } catch (e: NumberParseException) {
            trimmed
        }
    }
}
