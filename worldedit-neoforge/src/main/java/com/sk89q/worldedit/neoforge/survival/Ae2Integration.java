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

import appeng.api.networking.IGrid;
import appeng.items.tools.powered.WirelessTerminalItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * All of the Applied Energistics 2 specific code lives behind this class.
 *
 * <p>It must never be referenced unless {@code ae2} is loaded, since the AE2 classes it links
 * against are not present otherwise. {@link SurvivalMode} enforces that.</p>
 */
public final class Ae2Integration {

    private Ae2Integration() {
    }

    /**
     * Opens the stock of the ME network reachable through a wireless terminal the player carries.
     *
     * @return the stock, or {@code null} if no terminal is linked to a network
     */
    public static @Nullable ResourceStock openStock(ServerPlayer player) {
        IGrid grid = findGrid(player);
        return grid == null ? null : new Ae2Stock(player, grid);
    }

    /**
     * Finds a grid reachable through a wireless terminal carried by the player.
     *
     * <p>Terminals are accepted from the main hand, the off hand, the whole inventory, and from
     * Curios slots. Applied Energistics 2's own terminals and the terminals added by AE2WTLib are
     * both {@link WirelessTerminalItem}s, so a single instance check covers all of them.</p>
     */
    public static @Nullable IGrid findGrid(ServerPlayer player) {
        for (ItemStack stack : findTerminals(player)) {
            WirelessTerminalItem terminal = (WirelessTerminalItem) stack.getItem();
            IGrid grid = terminal.getLinkedGrid(stack, player.level(), error -> {
            });
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    public static boolean hasGrid(ServerPlayer player) {
        return findGrid(player) != null;
    }

    private static List<ItemStack> findTerminals(ServerPlayer player) {
        List<ItemStack> found = new ArrayList<>();

        // Hands first, then the inventory, then Curios.
        addIfTerminal(found, player.getMainHandItem());
        addIfTerminal(found, player.getOffhandItem());

        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addIfTerminal(found, inventory.getItem(slot));
        }

        for (ItemStack stack : CuriosSlots.getCurioStacks(player)) {
            addIfTerminal(found, stack);
        }

        return found;
    }

    private static void addIfTerminal(List<ItemStack> found, ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof WirelessTerminalItem) {
            found.add(stack);
        }
    }
}
