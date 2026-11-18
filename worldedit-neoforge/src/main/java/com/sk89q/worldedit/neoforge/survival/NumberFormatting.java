package com.sk89q.worldedit.neoforge.survival;

import java.util.Locale;

final class NumberFormatting {
    private NumberFormatting() {
    }

    static String compact(long value) {
        if (value < 1_000L) {
            return Long.toString(value);
        }
        if (value < 1_000_000L) {
            return oneDecimal(value, 1_000L, "K");
        }
        if (value < 1_000_000_000L) {
            return oneDecimal(value, 1_000_000L, "M");
        }
        if (value < 1_000_000_000_000L) {
            return oneDecimal(value, 1_000_000_000L, "B");
        }
        return oneDecimal(value, 1_000_000_000_000L, "T");
    }

    private static String oneDecimal(long value, long divisor, String suffix) {
        double scaled = (double) value / divisor;
        if (scaled >= 100.0) {
            return String.format(Locale.ROOT, "%.0f%s", scaled, suffix);
        }
        if (scaled >= 10.0) {
            return String.format(Locale.ROOT, "%.0f%s", scaled, suffix);
        }
        return String.format(Locale.ROOT, "%.1f%s", scaled, suffix);
    }
}
