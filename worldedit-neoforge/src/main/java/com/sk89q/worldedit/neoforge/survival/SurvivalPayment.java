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

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Pays for an edit out of the player's ME network and inventory.
 *
 * <p>The whole cost is taken in one go, before a single block is written. Items come from the ME
 * network first and from the player's inventory second; if anything at all is missing the edit is
 * cancelled and nothing is taken and nothing is built.</p>
 */
final class SurvivalPayment {

    /**
     * The outcome of a charge.
     *
     * @param reason why the player could not pay, or {@code null} when they did
     * @param missing the items that were not covered, by item
     */
    record Result(@Nullable String reason, Map<Item, Long> missing) {

        static Result charged() {
            return new Result(null, Map.of());
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

        ResourceStock network = SurvivalStocks.openNetwork(player);
        InventoryStock inventory = new InventoryStock(player);

        Map<Item, Long> fromNetwork = new LinkedHashMap<>();
        Map<Item, Long> fromInventory = new LinkedHashMap<>();
        Map<Item, Long> missing = new LinkedHashMap<>();

        for (Map.Entry<Item, Long> entry : cost.entrySet()) {
            long required = entry.getValue();

            long fromMe = network == null ? 0 : Math.min(network.available(entry.getKey()), required);
            if (fromMe > 0) {
                fromNetwork.put(entry.getKey(), fromMe);
            }
            required -= fromMe;

            long fromPlayer = Math.min(inventory.available(entry.getKey()), required);
            if (fromPlayer > 0) {
                fromInventory.put(entry.getKey(), fromPlayer);
            }
            required -= fromPlayer;

            if (required > 0) {
                missing.put(entry.getKey(), required);
            }
        }

        if (!missing.isEmpty()) {
            String where = network == null
                ? " (no wireless terminal linked to an ME network was found)"
                : "";
            return new Result("missing " + ItemLedger.describe(missing) + where, missing);
        }

        if (!fromNetwork.isEmpty() && !network.take(fromNetwork)) {
            return new Result("the ME network could not hand over the items", Map.of());
        }
        if (!fromInventory.isEmpty() && !inventory.take(fromInventory)) {
            // The player moved the items between the count and the take. Give the network part
            // back rather than leaving them out of pocket.
            SurvivalStocks.store(player, fromNetwork);
            return new Result("you no longer have the items for this edit", Map.of());
        }

        return Result.charged();
    }
}
