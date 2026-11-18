package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry and selection policy for payment providers. */
public final class PaymentProviders {
    public static final String MODE_AUTO = "auto";
    private static final Map<String, PaymentProvider> PROVIDERS = new LinkedHashMap<>();
    private static String preference = MODE_AUTO;

    static {
        register(new Ae2PaymentProvider());
        register(new SophisticatedBackpacksPaymentProvider());
        register(new ChestPaymentProvider());
        register(new InventoryPaymentProvider());
    }

    private PaymentProviders() {}

    public static void register(PaymentProvider provider) {
        PROVIDERS.put(provider.id(), provider);
    }

    public static String preference() { return preference; }

    public static boolean setPreference(String mode) {
        if (MODE_AUTO.equalsIgnoreCase(mode) || PROVIDERS.containsKey(mode.toLowerCase())) {
            preference = MODE_AUTO.equalsIgnoreCase(mode) ? MODE_AUTO : mode.toLowerCase();
            return true;
        }
        return false;
    }

    public static List<PaymentProvider> providers() { return List.copyOf(PROVIDERS.values()); }
    public static PaymentProvider provider(String id) { return PROVIDERS.get(id); }

    public static boolean atLeastOneAvailable(ServerPlayer player) {
        for (PaymentProvider provider : PROVIDERS.values()) {
            if (provider.available(player)) {
                return true;
            }
        }
        return false;
    }

    public static List<ResourceStock> stockOrder(ServerPlayer player) {
        List<ResourceStock> stocks = new ArrayList<>();

        if (MODE_AUTO.equals(preference)) {
            for (PaymentProvider provider : PROVIDERS.values()) {
                if (provider.available(player)) {
                    ResourceStock stock = provider.open(player);
                    if (stock != null) {
                        stocks.add(stock);
                    }
                }
            }
        } else {
            PaymentProvider selected = PROVIDERS.get(preference);
            if (selected != null && selected.available(player)) {
                ResourceStock stock = selected.open(player);
                if (stock != null) {
                    stocks.add(stock);
                }
            }
        }
        return stocks;
    }

    public static String status(ServerPlayer player) {
        if (!MODE_AUTO.equals(preference)) {
            PaymentProvider selected = PROVIDERS.get(preference);
            if (selected != null) {
                return selected.displayName() + (selected.available(player) ? " (available)" : " (unavailable)");
            }
        }
        List<String> available = new ArrayList<>();
        for (PaymentProvider provider : PROVIDERS.values()) {
            if (provider.available(player)) {
                available.add(provider.displayName());
            }
        }
        return "Auto (" + String.join(", ", available) + ")";
    }
}
