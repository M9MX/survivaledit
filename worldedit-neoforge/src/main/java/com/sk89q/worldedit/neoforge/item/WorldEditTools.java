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

package com.sk89q.worldedit.neoforge.item;

import com.sk89q.worldedit.neoforge.NeoForgeWorldEdit;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The item tag that marks the items SurvivalEdit acts on: the Selection Axe and every stick.
 *
 * <p>It is a datapack tag so the use delay and anything else that treats an item as a tool agree
 * on one list, and a pack can add its own tools without a code change.</p>
 */
public final class WorldEditTools {

    public static final TagKey<Item> TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath(NeoForgeWorldEdit.MOD_ID, "tools"));

    private WorldEditTools() {
    }

    public static boolean isTool(ItemStack stack) {
        return !stack.isEmpty() && stack.is(TAG);
    }
}
