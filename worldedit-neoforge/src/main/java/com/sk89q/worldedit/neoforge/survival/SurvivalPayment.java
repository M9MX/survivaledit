/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Pays for an edit out of the available payment providers.
 *
 * <p>The whole cost is taken in one go, before a single block is written. Providers are checked in
 * the configured order; if anything is missing, the edit is cancelled and nothing is taken.</p>
 */
final class SurvivalPayment {

    /**
     * The outcome of a charge.
     *
     * @param reason why the player could not pay, or {@code null} when they did
     * @param missing the items that were not covered, by item
     */
    record Result(@Nullable String reason, Map<Item, Long> missing,
                  Map<Item, Map<String, Long>> sources) {

        static Result charged() {
            return new Result(null, Map.of(), Map.of());
        }

        static Result charged(Map<Item, Map<String, Long>> sources) {
            return new Result(null, Map.of(), sources);
        }

        boolean paid() {
            return reason == null;
        }
    }

    private SurvivalPayment() {
    }

    /**
     * Charges the player for an edit.
     *
     * @param cost the items the edit needs, one per placed block
     * @return the outcome, which names what is missing when the player could not pay
     */
    static Result charge(ServerPlayer player, Map<Item, Long> cost) {
        if (cost.isEmpty()) {
            return Result.charged();
        }

        List<ResourceStock> stocks = PaymentProviders.stockOrder(player);
        Map<Item, Long> missing = new LinkedHashMap<>();
        LinkedHashMap<ResourceStock, Map<Item, Long>> allocations =
            new LinkedHashMap<>();

        for (Map.Entry<Item, Long> entry : cost.entrySet()) {
            long required = entry.getValue();
            for (ResourceStock stock : stocks) {
                if (required <= 0) {
                    break;
                }
                long taken = Math.min(stock.available(entry.getKey()), required);
                if (taken > 0) {
                    allocations.computeIfAbsent(stock, ignored -> new LinkedHashMap<>())
                        .put(entry.getKey(), taken);
                    required -= taken;
                }
            }

            if (required > 0) {
                missing.put(entry.getKey(), required);
            }
        }

        if (!missing.isEmpty()) {
            String mode = PaymentProviders.preference();
            String source = mode.equals(PaymentProviders.MODE_AUTO)
                ? "the selected payment providers"
                : "'" + mode + "'";
            return new Result("missing " + ItemLedger.describe(missing)
                + " from " + source + ".", missing, Map.of());
        }

        List<ResourceStock> taken = new ArrayList<>();
        Map<Item, Map<String, Long>> sources = new LinkedHashMap<>();
        for (Map.Entry<ResourceStock, Map<Item, Long>> allocation : allocations.entrySet()) {
            if (!allocation.getKey().take(allocation.getValue())) {
                for (ResourceStock stock : taken) {
                    Map<Item, Long> returned = allocations.get(stock);
                    if (returned != null) {
                        stock.give(returned);
                    }
                }
                return new Result("a payment provider could not hand over the items", Map.of(), Map.of());
            }
            for (Map.Entry<Item, Long> entry : allocation.getValue().entrySet()) {
                sources.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>())
                    .merge(allocation.getKey().sourceId(), entry.getValue(), Long::sum);
            }
            taken.add(allocation.getKey());
        }
        if (taken.isEmpty() && !cost.isEmpty()) {
            return new Result("you no longer have the items for this edit", Map.of(), Map.of());
        }

        return Result.charged(sources);
    }
}
