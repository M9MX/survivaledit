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
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * The Selection Axe, which takes the place of WorldEdit's wooden wand, and the WorldEdit Sticks.
 *
 * <p>The axe is the only item that selects positions: the wand item setting points at it, and in
 * survival it has to be crafted instead of handed out with {@code //wand}.</p>
 *
 * <p>The sticks are brush holders. WorldEdit binds a tool to an item type, so one stick per dye
 * colour gives a player a separate brush slot for every colour. What is bound to a stick is shown
 * in its lore by {@link BrushSticks}.</p>
 */
public final class SurvivalEditItems {

    public static final String AXE_ID = NeoForgeWorldEdit.MOD_ID + ":axe";

    /** The dye colours a stick can be made in, in dye order. */
    public static final List<String> STICK_COLORS = List.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NeoForgeWorldEdit.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, NeoForgeWorldEdit.MOD_ID);

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

    /** The plain stick the coloured ones are made from. */
    public static final DeferredItem<Item> STICK_MAIN = registerStick("stick_main");

    /** Every stick, the main one first and then one per dye colour. */
    public static final List<DeferredItem<Item>> STICKS;

    static {
        List<DeferredItem<Item>> sticks = new ArrayList<>();
        sticks.add(STICK_MAIN);
        for (String color : STICK_COLORS) {
            sticks.add(registerStick("stick_" + color));
        }
        STICKS = List.copyOf(sticks);
    }

    private static DeferredItem<Item> registerStick(String name) {
        return ITEMS.register(name, () -> new Item(new Item.Properties()
            // One brush per stick: two brushes on one stack would fight over the same tool.
            .stacksTo(1)
            .fireResistant()
            .component(DataComponents.LORE, new ItemLore(BrushSticks.unboundLore()))));
    }

    static {
        TABS.register("survivaledit", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.worldedit.survivaledit"))
            .icon(() -> new ItemStack(AXE.get()))
            .displayItems((parameters, output) -> {
                output.accept(AXE.get());
                for (DeferredItem<Item> stick : STICKS) {
                    output.accept(stick.get());
                }
            })
            .build());
    }

    private SurvivalEditItems() {
    }
}
