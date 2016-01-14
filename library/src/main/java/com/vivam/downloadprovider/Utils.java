package com.vivam.downloadprovider;

import android.content.Intent;

import java.util.Locale;

/**
 * Some methods from android source code.
 */
public class Utils {

    /**
     * Normalize a MIME data type.
     *
     * <p>A normalized MIME type has white-space trimmed,
     * content-type parameters removed, and is lower-case.
     * This aligns the type with Android best practices for
     * intent filtering.
     *
     * <p>For example, "text/plain; charset=utf-8" becomes "text/plain".
     * "text/x-vCard" becomes "text/x-vcard".
     *
     * <p>All MIME types received from outside Android (such as user input,
     * or external sources like Bluetooth, NFC, or the Internet) should
     * be normalized before they are used to create an Intent.
     *
     * @param type MIME data type to normalize
     * @return normalized MIME data type, or null if the input was null
     * @see Intent#normalizeMimeType(String)
     */
    public static String normalizeMimeType(String type) {
        if (type == null) {
            return null;
        }

        type = type.trim().toLowerCase(Locale.ROOT);

        final int semicolonIndex = type.indexOf(';');
        if (semicolonIndex != -1) {
            type = type.substring(0, semicolonIndex);
        }
        return type;
    }
}
