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
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * The Selection Axe, which takes the place of WorldEdit's wooden wand.
 *
 * <p>It is the only item that selects positions: the wand item setting points at it, and in
 * survival it has to be crafted instead of handed out with {@code //wand}.</p>
 */
public final class SurvivalEditItems {

    public static final String AXE_ID = NeoForgeWorldEdit.MOD_ID + ":axe";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NeoForgeWorldEdit.MOD_ID);

    public static final DeferredItem<Item> AXE = ITEMS.register("axe", () ->
        new AxeItem(Tiers.NETHERITE, new Item.Properties()
            .stacksTo(1)
            .fireResistant()
            // A selection tool that wears out would just be a nuisance.
            .component(DataComponents.UNBREAKABLE, new Unbreakable(false))
            .component(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Marks the corners of an edit region:").withStyle(ChatFormatting.GRAY),
                Component.literal("left click for the first, right for the second.").withStyle(ChatFormatting.GRAY),
                Component.literal("Survival edits are paid from your ME network").withStyle(ChatFormatting.GRAY),
                Component.literal("or your inventory, and undo gives the blocks back.").withStyle(ChatFormatting.GRAY)
            )))
            .attributes(AxeItem.createAttributes(Tiers.NETHERITE, 5.0F, -3.0F))));

    private SurvivalEditItems() {
    }

    public static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(AXE.get());
        }
    }
}
