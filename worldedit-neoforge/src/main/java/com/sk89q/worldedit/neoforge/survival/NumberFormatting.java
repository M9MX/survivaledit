package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

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

    static MutableComponent hoverCount(long value) {
        return Component.literal(compact(value))
            .withStyle(style -> style.withHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                Component.literal(Long.toString(value)))));
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
