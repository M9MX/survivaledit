package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player preview state for the next paid WorldEdit operation. */
public final class PaymentPreview {
    private static final Map<UUID, Boolean> ENABLED = new HashMap<>();

    private PaymentPreview() {
    }

    public static boolean enabled(ServerPlayer player) {
        return ENABLED.getOrDefault(player.getUUID(), false);
    }

    public static void set(ServerPlayer player, boolean enabled) {
        if (enabled) {
            ENABLED.put(player.getUUID(), true);
        } else {
            ENABLED.remove(player.getUUID());
        }
    }

    public static void consume(ServerPlayer player) {
        ENABLED.remove(player.getUUID());
    }

    public static Map<Item, Long> available(ServerPlayer player) {
        // This method is intentionally unused for bulk inventory snapshots.
        // Availability is checked only for item types in the operation cost.
        return Map.of();
    }

    public static Map<Item, Long> missing(ServerPlayer player, Map<Item, Long> cost) {
        Map<Item, Long> missing = new java.util.LinkedHashMap<>();
        java.util.List<ResourceStock> stocks = PaymentProviders.stockOrder(player);
        for (Map.Entry<Item, Long> entry : cost.entrySet()) {
            long required = entry.getValue();
            for (ResourceStock stock : stocks) {
                long available = stock.available(entry.getKey());
                if (available >= required) {
                    required = 0;
                    break;
                }
                required -= available;
            }
            if (required > 0) {
                missing.put(entry.getKey(), required);
            }
        }
        return missing;
    }
}
