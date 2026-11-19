/*
 * SurvivalEdit, a WorldEdit fork tuned for survival play
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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Decides which block entity data travels with a block.
 *
 * <p>Appearance data has to survive a copy: a copycat only knows what it is mimicking in its block
 * entity, and so does a framed block. Everything else is left behind, and containers are refused
 * outright, because a copied inventory would be a paste that duplicates items nobody paid for.</p>
 */
final class SurvivalBlockEntities {

    private SurvivalBlockEntities() {
    }

    /** Whether the data of this block entity may be copied, pasted and restored by undo. */
    static boolean isCopyable(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        // A container is never copied, whatever it holds: its contents are the player's items.
        if (blockEntity instanceof Container) {
            return false;
        }
        return isAppearanceEntity(blockEntity.getClass().getName());
    }

    /** Whether this block entity belongs to FramedBlocks or a copycat mod. */
    static boolean isAppearanceEntity(String className) {
        if (className.startsWith("xfacthd.framedblocks.")) {
            return true;
        }
        // Create's CopycatBlockEntity and the Copycats+ variants all name themselves for it.
        return className.toLowerCase(Locale.ROOT).contains("copycat");
    }

    /** Whether a tag carries items, which must never leave the block they are in. */
    static boolean hasItemContents(CompoundTag tag) {
        return SurvivalMode.compoundHasItemContents(tag);
    }
}
