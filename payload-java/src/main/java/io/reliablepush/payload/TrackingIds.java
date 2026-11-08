package io.reliablepush.payload;

import java.util.UUID;

/** Tracking ids: random UUID v4 strings, the value of {@code rp_tid} and the {@code trackingId} of the sender API. */
public final class TrackingIds {

    private static final int LENGTH = 36;

    private TrackingIds() {
    }

    /** A new random UUID v4 in lower case, for example {@code 6f1c2b9e-5d1a-4c55-9a38-0c1f3a7d9e11}. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Whether {@code id} is a UUID in its canonical 8-4-4-4-12 hexadecimal form (either case, any version).
     * {@code null} is not valid.
     */
    public static boolean isValid(String id) {
        if (id == null || id.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
