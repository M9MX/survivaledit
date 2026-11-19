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

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.Tool;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.neoforge.NeoForgeWorldEdit;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the lore of the WorldEdit Sticks in step with the brush bound to them.
 *
 * <p>WorldEdit stores a tool per item type in the player's session, so the lore is built from the
 * session rather than saved on the item: two sticks of one colour cannot disagree with what the
 * brush will actually do.</p>
 */
public final class BrushSticks {

    /** Every stick, so a datapack tag can be the single source of truth for "is this a stick". */
    public static final TagKey<Item> TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath(NeoForgeWorldEdit.MOD_ID, "sticks"));

    private static final int REFRESH_INTERVAL_TICKS = 10;

    private static int ticks;

    private BrushSticks() {
    }

    /** The lore of a stick that has no brush bound yet. */
    public static List<Component> unboundLore() {
        return List.of(
            Component.literal("No brush bound").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
            Component.literal("Hold and run /brush <type> <material>").withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("then /material, /mask, /size and /range.").withStyle(ChatFormatting.DARK_GRAY)
        );
    }

    /**
     * Refreshes every player's stick once in a while, which catches a stick that was switched into
     * the hand after the brush on that colour was last changed.
     */
    public static void tick(MinecraftServer server) {
        if (++ticks < REFRESH_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    /** Rewrites the lore of the item in the player's main hand, if it is a stick. */
    public static void refresh(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(TAG)) {
            return;
        }
        NeoForgeWorldEdit mod = NeoForgeWorldEdit.inst;
        if (mod == null) {
            return;
        }
        LocalSession session = mod.getSession(player);
        Tool tool = session.getTool(NeoForgeAdapter.adapt(stack.getItem()));

        List<Component> lines = describe(tool);
        ItemLore current = stack.get(DataComponents.LORE);
        if (current != null && current.lines().equals(lines)) {
            return;
        }
        // A changed lore is synced with the rest of the inventory on the next player tick.
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    private static List<Component> describe(@Nullable Tool tool) {
        if (tool == null) {
            return unboundLore();
        }
        if (!(tool instanceof BrushTool brush)) {
            return List.of(
                Component.literal("Tool: " + strip(tool.getClass().getSimpleName(), "Tool"))
                    .withStyle(ChatFormatting.AQUA)
            );
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Brush: " + strip(brush.getBrush().getClass().getSimpleName(), "Brush"))
            .withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal("Size: " + formatSize(brush.getSize()) + "   Range: " + brush.getRange())
            .withStyle(ChatFormatting.GRAY));

        Pattern material = brush.getMaterial();
        if (material == null) {
            lines.add(Component.literal("Material: brush default").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal("Material: " + describePattern(material)).withStyle(ChatFormatting.GRAY));
        }

        lines.add(maskLine("Mask: ", brush.getMask()));
        Mask traceMask = brush.getTraceMask();
        if (traceMask != null) {
            lines.add(maskLine("Trace mask: ", traceMask));
        }
        return lines;
    }

    private static Component maskLine(String label, @Nullable Mask mask) {
        if (mask == null) {
            return Component.literal(label + "none").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.literal(label + describeMask(mask)).withStyle(ChatFormatting.GRAY);
    }

    private static String describePattern(Pattern pattern) {
        if (pattern instanceof BlockStateHolder<?> state) {
            return state.getBlockType().id();
        }
        return strip(pattern.getClass().getSimpleName(), "Pattern");
    }

    private static String describeMask(Mask mask) {
        // Masks have no display name, so the class name is the next best description. It still
        // tells the player what they bound, which is what the lore is for.
        return strip(mask.getClass().getSimpleName(), "Mask");
    }

    private static String formatSize(double size) {
        if (size == Math.rint(size)) {
            return Integer.toString((int) size);
        }
        return String.format(Locale.ROOT, "%.1f", size);
    }

    /** Turns a class name into something a player can read: {@code BlockMask} becomes {@code Block}. */
    private static String strip(String name, String suffix) {
        String stripped = name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
        return stripped.isEmpty() ? name : stripped;
    }
}
