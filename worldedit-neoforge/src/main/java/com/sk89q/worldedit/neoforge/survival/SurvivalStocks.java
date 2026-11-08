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
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Hands items back to a player: the ME network first, then the inventory, and only then the ground.
 */
public final class SurvivalStocks {

    private SurvivalStocks() {
    }

    /**
     * Stores the items wherever they fit.
     */
    public static void store(ServerPlayer player, Map<Item, Long> amounts) {
        Map<Item, Long> leftover = amounts;

        ResourceStock network = openNetwork(player);
        if (network != null) {
            leftover = network.give(leftover);
        }
        if (!leftover.isEmpty()) {
            leftover = new InventoryStock(player).give(leftover);
        }
        if (!leftover.isEmpty()) {
            drop(player, leftover);
        }
    }

    /**
     * Opens the player's network, or {@code null} if they have no terminal linked to one.
     */
    public static @Nullable ResourceStock openNetwork(ServerPlayer player) {
        if (!SurvivalMode.requiredModsLoaded()) {
            return null;
        }
        try {
            return Ae2Integration.openStock(player);
        } catch (Throwable ignored) {
            // AE2 failing to answer must never break an edit or an undo.
            return null;
        }
    }

    private static void drop(ServerPlayer player, Map<Item, Long> amounts) {
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            int maxStackSize = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            long remaining = entry.getValue();
            while (remaining > 0) {
                int size = (int) Math.min(remaining, maxStackSize);
                player.drop(new ItemStack(entry.getKey(), size), false);
                remaining -= size;
            }
        }
    }
}
