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

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Applied Energistics 2 storage of the network reached through the player's wireless terminal.
 *
 * <p>Only what the network is holding right now counts. Nothing is ever crafted or requested, so an
 * edit that the network cannot cover in stored items is refused instead of waiting on a craft.</p>
 */
final class Ae2Stock implements ResourceStock {

    private final ServerPlayer player;
    private final MEStorage storage;

    Ae2Stock(ServerPlayer player, IGrid grid) {
        this.player = player;
        this.storage = grid.getStorageService().getInventory();
    }

    private static AEItemKey key(Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    @Override
    public long available(Item item) {
        return storage.extract(key(item), Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.ofPlayer(player));
    }

    @Override
    public boolean take(Map<Item, Long> amounts) {
        IActionSource source = IActionSource.ofPlayer(player);

        // Simulate the whole order first, so a request the network cannot fill takes nothing.
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            if (storage.extract(key(entry.getKey()), entry.getValue(), Actionable.SIMULATE, source) < entry.getValue()) {
                return false;
            }
        }

        Map<AEItemKey, Long> taken = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            AEItemKey itemKey = key(entry.getKey());
            long got = storage.extract(itemKey, entry.getValue(), Actionable.MODULATE, source);
            taken.merge(itemKey, got, Long::sum);
            if (got < entry.getValue()) {
                // Somebody else changed the network between the simulation and this call. Put back
                // whatever was already removed so the player is not out of pocket.
                for (Map.Entry<AEItemKey, Long> back : taken.entrySet()) {
                    storage.insert(back.getKey(), back.getValue(), Actionable.MODULATE, source);
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<Item, Long> give(Map<Item, Long> amounts) {
        IActionSource source = IActionSource.ofPlayer(player);
        Map<Item, Long> leftover = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long inserted = storage.insert(key(entry.getKey()), entry.getValue(), Actionable.MODULATE, source);
            long left = entry.getValue() - inserted;
            if (left > 0) {
                leftover.put(entry.getKey(), left);
            }
        }
        return leftover;
    }
}
