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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The items a player is carrying. This is the fallback payer when an edit is not covered by an ME
 * network, and the last stop for blocks an undo gives back.
 */
final class InventoryStock implements ResourceStock {

    private final ServerPlayer player;

    InventoryStock(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public long available(Item item) {
        Inventory inventory = player.getInventory();
        long total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public boolean take(Map<Item, Long> amounts) {
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            if (available(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        Inventory inventory = player.getInventory();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !stack.is(entry.getKey())) {
                    continue;
                }
                int taken = (int) Math.min(stack.getCount(), remaining);
                stack.shrink(taken);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
                remaining -= taken;
            }
        }
        inventory.setChanged();
        return true;
    }

    @Override
    public Map<Item, Long> give(Map<Item, Long> amounts) {
        Map<Item, Long> leftover = new LinkedHashMap<>();
        Inventory inventory = player.getInventory();

        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            int maxStackSize = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            while (remaining > 0) {
                ItemStack stack = new ItemStack(entry.getKey(), (int) Math.min(remaining, maxStackSize));
                if (!inventory.add(stack)) {
                    leftover.merge(entry.getKey(), (long) stack.getCount(), Long::sum);
                }
                remaining -= Math.min(remaining, maxStackSize);
            }
        }
        inventory.setChanged();
        return leftover;
    }
}
