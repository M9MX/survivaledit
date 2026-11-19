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
import com.sk89q.worldedit.neoforge.internal.NBTConverter;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Turns blocks into the items that pay for them, and back.
 *
 * <p>A ledger is simply a count of items, which makes it the common currency of the ME network, the
 * player's inventory and the payment engine.</p>
 *
 * <p>One block can need more than one item. A candle block with four candles needs four candles, and
 * a copycat or framed block needs the block it is dressed up as in addition to the copycat itself.
 * Both are what the block is worth, so both are what the survival edit charges for.</p>
 */
public final class ItemLedger {

    /** State properties that hold a number of items rather than a shape. */
    private static final Set<String> COUNT_PROPERTIES =
        Set.of("candles", "pickles", "eggs", "amount", "segment_amount");

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
        return item(id);
    }

    /**
     * Counts the items one block needs.
     *
     * <p>The block itself is always paid with its own item. When the block carries appearance data,
     * the material it is dressed up as is paid for as well: otherwise a player could dress a cheap
     * copycat in diamonds for the price of the copycat.</p>
     *
     * @return the items and amounts, empty for blocks without an item form
     */
    public static Map<Item, Long> costOf(BaseBlock block) {
        Map<Item, Long> cost = new LinkedHashMap<>();
        Item own = itemFor(block);
        if (own != null) {
            // A candle block of four candles is four candles, not one.
            merge(cost, own, units(block));
        }
        LinCompoundTag nbt = block.getNbt();
        if (nbt != null) {
            CompoundTag tag = NBTConverter.toNative(nbt);
            // Only appearance data is read: a container's contents are copied nowhere, so they are
            // not part of the price either.
            if (SurvivalBlockEntities.isAppearanceTag(tag)) {
                for (Map.Entry<Item, Long> entry : materialsOf(tag).entrySet()) {
                    merge(cost, entry.getKey(), entry.getValue());
                }
            }
        }
        return cost;
    }

    /**
     * Adds the cost of one block to a running ledger.
     */
    public static void addCost(Map<Item, Long> into, BaseBlock block) {
        for (Map.Entry<Item, Long> entry : costOf(block).entrySet()) {
            into.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
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
            addCost(cost, block);
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

    // ------------------------------------------------------------------
    // Amounts
    // ------------------------------------------------------------------

    /** How many items one block of this state is made of. */
    private static long units(BaseBlock block) {
        BlockState state = SurvivalEditJob.nativeState(block);
        if (state == null) {
            return 1;
        }
        if (state.hasProperty(BlockStateProperties.CANDLES)) {
            return state.getValue(BlockStateProperties.CANDLES);
        }
        if (state.hasProperty(BlockStateProperties.PICKLES)) {
            return state.getValue(BlockStateProperties.PICKLES);
        }
        if (state.hasProperty(BlockStateProperties.EGGS)) {
            return state.getValue(BlockStateProperties.EGGS);
        }
        if (state.hasProperty(PinkPetalsBlock.AMOUNT)) {
            return state.getValue(PinkPetalsBlock.AMOUNT);
        }
        return 1;
    }

    private static void merge(Map<Item, Long> cost, Item item, long amount) {
        if (amount <= 0) {
            return;
        }
        // The same material can be written twice in a block entity, once as a block state and once
        // as an item stack. Whichever names the larger amount is the real cost.
        cost.merge(item, amount, Math::max);
    }

    // ------------------------------------------------------------------
    // Copied material
    // ------------------------------------------------------------------

    /**
     * Finds the material a copycat or framed block is dressed up as.
     *
     * <p>Each mod keeps its own idea of where that lives: Create writes a block state and the item
     * that was consumed, FramedBlocks writes a camo container holding a stack. Both forms are read
     * the same way, so the cost does not depend on which mod placed the block.</p>
     */
    private static Map<Item, Long> materialsOf(CompoundTag tag) {
        Map<Item, Long> found = new LinkedHashMap<>();
        scan(tag, 0, found);
        return found;
    }

    private static void scan(Tag tag, int depth, Map<Item, Long> found) {
        if (depth > 8) {
            return;
        }
        if (tag instanceof CompoundTag compound) {
            blockStateIn(compound, found);
            stackIn(compound, found);
            for (String key : compound.getAllKeys()) {
                scan(compound.get(key), depth + 1, found);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                scan(element, depth + 1, found);
            }
        }
    }

    /**
     * Whether an id names the copycat or framed block itself rather than what it is dressed up as.
     *
     * <p>Copycats+ writes the copycat base as the material of an undressed face and as the used
     * item, which is structure rather than a second block of material.</p>
     */
    private static boolean isAppearancePart(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("copycat") || lower.startsWith("framedblocks:");
    }

    /** Reads a {@code {Name: "minecraft:stone", Properties: {...}}} block state. */
    private static void blockStateIn(CompoundTag compound, Map<Item, Long> found) {
        String name = compound.getString("Name");
        if (name.isEmpty() || isAppearancePart(name)) {
            return;
        }
        Item item = item(ResourceLocation.tryParse(name));
        if (item == null) {
            return;
        }
        long units = 1;
        CompoundTag properties = compound.getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            if (!COUNT_PROPERTIES.contains(key)) {
                continue;
            }
            try {
                units = Math.max(units, Long.parseLong(properties.getString(key)));
            } catch (NumberFormatException ignored) {
                // A property that is not a number of items is not a count.
            }
        }
        merge(found, item, units);
    }

    /** Reads a {@code {id: "minecraft:stone", count: 1}} item stack. */
    private static void stackIn(CompoundTag compound, Map<Item, Long> found) {
        String id = compound.getString("id");
        if (isAppearancePart(id)) {
            return;
        }
        Item item = item(ResourceLocation.tryParse(id));
        if (item == null) {
            return;
        }
        long count = 1;
        if (compound.contains("count", Tag.TAG_ANY_NUMERIC)) {
            count = compound.getInt("count");
        } else if (compound.contains("Count", Tag.TAG_ANY_NUMERIC)) {
            count = compound.getByte("Count");
        }
        merge(found, item, count);
    }

    private static @Nullable Item item(@Nullable ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item != Items.AIR) {
            return item;
        }
        if (block != null) {
            Item blockItem = block.asItem();
            if (blockItem != Items.AIR) {
                return blockItem;
            }
        }
        return null;
    }
}
