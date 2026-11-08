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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Turns blocks into the items that pay for them, and back.
 *
 * <p>A ledger is simply a count of items, which makes it the common currency of the ME network, the
 * player's inventory and the payment engine.</p>
 */
public final class ItemLedger {

    private ItemLedger() {
    }

    /**
     * Gets the item a block is paid with.
     *
     * @return the item, or {@code null} if the block has no item form (air, water, fire, ...)
     */
    public static @Nullable Item itemFor(BlockStateHolder<?> block) {
        if (block.getBlockType().getMaterial().isAir()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(block.getBlockType().id());
        if (id == null) {
            return null;
        }
        Block mcBlock = BuiltInRegistries.BLOCK.get(id);
        if (mcBlock == null) {
            return null;
        }
        Item item = mcBlock.asItem();
        if (item == Items.AIR) {
            return null;
        }
        return item;
    }

    /**
     * Counts the items needed to place every block of an edit.
     *
     * <p>Blocks without an item form cost nothing. The blocks they replace are voided, so they are
     * not part of the cost.</p>
     */
    public static Map<Item, Long> costOf(BlockMap<BaseBlock> blocks) {
        Map<Item, Long> cost = new LinkedHashMap<>();
        for (BlockVector3 position : blocks.keySet()) {
            BaseBlock block = blocks.get(position);
            if (block == null) {
                continue;
            }
            Item item = itemFor(block);
            if (item != null) {
                cost.merge(item, 1L, Long::sum);
            }
        }
        return cost;
    }

    public static long total(Map<Item, Long> ledger) {
        long total = 0;
        for (long amount : ledger.values()) {
            total += amount;
        }
        return total;
    }

    public static ItemStack stackOf(Item item, int amount) {
        return new ItemStack(item, amount);
    }

    /**
     * Renders a ledger as "Stone x64, Dirt x12" for chat messages.
     */
    public static String describe(Map<Item, Long> ledger) {
        StringBuilder builder = new StringBuilder();
        ledger.entrySet().stream()
            .sorted(Map.Entry.comparingByKey((a, b) -> a.getDescriptionId().compareTo(b.getDescriptionId())))
            .limit(6)
            .forEach(entry -> {
                if (!builder.isEmpty()) {
                    builder.append(", ");
                }
                builder.append(new ItemStack(entry.getKey()).getHoverName().getString())
                    .append(" x").append(entry.getValue());
            });
        if (ledger.size() > 6) {
            builder.append(", ...");
        }
        return builder.toString();
    }
}
