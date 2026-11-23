package com.meme.vwallet.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PhoneNumberUtilsTest {

    @Test
    @DisplayName("Normalize phone number starting with +84 to standard 10-digit format starting with 0")
    void normalize_withPlus84Prefix_returnsNormalizedPhone() {
        assertEquals("0912345678", PhoneNumberUtils.normalize("+84912345678"));
        assertEquals("0381234567", PhoneNumberUtils.normalize("+84381234567"));
        assertEquals("0561234567", PhoneNumberUtils.normalize("+84561234567"));
        assertEquals("0771234567", PhoneNumberUtils.normalize("+84771234567"));
        assertEquals("0881234567", PhoneNumberUtils.normalize("+84881234567"));
    }

    @Test
    @DisplayName("Normalize phone number already starting with 0 returns identical 10-digit format")
    void normalize_withDomesticPrefix_returnsSamePhone() {
        assertEquals("0912345678", PhoneNumberUtils.normalize("0912345678"));
        assertEquals("0381234567", PhoneNumberUtils.normalize("0381234567"));
        assertEquals("0561234567", PhoneNumberUtils.normalize("0561234567"));
        assertEquals("0771234567", PhoneNumberUtils.normalize("0771234567"));
        assertEquals("0881234567", PhoneNumberUtils.normalize("0881234567"));
    }

    @Test
    @DisplayName("Normalize trims leading and trailing whitespaces before normalizing")
    void normalize_withWhitespace_trimsAndNormalizes() {
        assertEquals("0912345678", PhoneNumberUtils.normalize("  +84912345678  "));
        assertEquals("0912345678", PhoneNumberUtils.normalize("\t0912345678\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "0123456789",    // prefix 01 is invalid in Vietnam
            "0223456789",    // prefix 02 is landline, not mobile
            "0423456789",    // prefix 04 invalid mobile
            "0623456789",    // prefix 06 invalid mobile
            "091234567",     // 9 digits (too short)
            "09123456789",   // 11 digits (too long)
            "+84123456789",  // +84 with invalid mobile prefix 1
            "+8491234567",   // +84 too short
            "+849123456789", // +84 too long
            "098765432a",    // non-digit character
            "not-a-phone",
            "+84 912345678"  // space inside number
    })
    @DisplayName("Normalize throws IllegalArgumentException for invalid phone formats")
    void normalize_invalidPhone_throwsIllegalArgumentException(String invalidPhone) {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtils.normalize(invalidPhone));
    }

    @Test
    @DisplayName("Normalize null throws IllegalArgumentException")
    void normalize_null_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtils.normalize(null));
    }

    @Test
    @DisplayName("isValid returns true for valid formats and false for invalid ones")
    void isValid_validAndInvalidCases() {
        assertTrue(PhoneNumberUtils.isValid("+84912345678"));
        assertTrue(PhoneNumberUtils.isValid("0912345678"));
        assertTrue(PhoneNumberUtils.isValid("  0912345678  "));

        assertFalse(PhoneNumberUtils.isValid(null));
        assertFalse(PhoneNumberUtils.isValid(""));
        assertFalse(PhoneNumberUtils.isValid("0123456789"));
        assertFalse(PhoneNumberUtils.isValid("091234567"));
        assertFalse(PhoneNumberUtils.isValid("invalid"));
    }
}
