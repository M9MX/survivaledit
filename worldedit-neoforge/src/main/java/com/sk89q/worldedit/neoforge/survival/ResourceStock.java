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

import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * A place items can be taken from and given back to, such as the player's inventory or an ME
 * network reached through a wireless terminal.
 *
 * <p>A stock can only be taken from: survival editing never asks a network to craft anything, so an
 * edit the player's storage does not cover is refused rather than queued. Every method works in
 * whole items and is driven from the server thread. The interface deliberately mentions no
 * mod-specific type, so the survival logic can be loaded without Applied Energistics 2 present.</p>
 */
public interface ResourceStock {

    /**
     * Gets how many of an item can be taken from this stock right now.
     */
    long available(Item item);

    /**
     * Removes every item of the ledger, all or nothing.
     *
     * @return whether the whole ledger was taken
     */
    boolean take(Map<Item, Long> amounts);

    /**
     * Stores the items of the ledger.
     *
     * @return the items this stock could not store
     */
    Map<Item, Long> give(Map<Item, Long> amounts);
}
