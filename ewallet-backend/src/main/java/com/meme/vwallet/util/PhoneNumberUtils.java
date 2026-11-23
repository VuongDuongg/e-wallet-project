package com.meme.vwallet.util;

import java.util.regex.Pattern;

public final class PhoneNumberUtils {

    public static final String PHONE_REGEX = "^(\\+84|0)[35789][0-9]{8}$";
    private static final Pattern PHONE_PATTERN = Pattern.compile(PHONE_REGEX);

    private PhoneNumberUtils() {
        // Utility class
    }

    public static String normalize(String rawPhone) {
        if (rawPhone == null) {
            throw new IllegalArgumentException("Số điện thoại không được để trống");
        }
        String trimmed = rawPhone.trim();
        if (!PHONE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Định dạng số điện thoại không hợp lệ: " + rawPhone);
        }
        if (trimmed.startsWith("+84")) {
            return "0" + trimmed.substring(3);
        }
        return trimmed;
    }

    public static boolean isValid(String rawPhone) {
        if (rawPhone == null) {
            return false;
        }
        return PHONE_PATTERN.matcher(rawPhone.trim()).matches();
    }
}
