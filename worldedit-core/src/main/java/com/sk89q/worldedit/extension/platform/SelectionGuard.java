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

package com.sk89q.worldedit.extension.platform;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;

import javax.annotation.Nullable;

/**
 * A veto on marking a selection corner, installed by the platform.
 *
 * <p>Every way of setting a corner goes through here — the selection wand, {@code //pos1},
 * {@code //pos2}, {@code //pos} and the {@code //hpos} variants — so a position that may not be
 * selected cannot be reached by picking a different command. The guard is asked before the corner is
 * set, and it reports its own refusal, because only the platform knows why the position is refused.
 * Refusing the corner rather than the finished region keeps the reason tied to the click that
 * caused it.</p>
 *
 * <p>Without a platform guard (console, or a platform that installs none) everything is selectable,
 * which is the behaviour WorldEdit has always had.</p>
 */
public interface SelectionGuard {

    /**
     * Whether this position may become a selection corner.
     *
     * <p>An implementation that returns {@code false} should tell the actor why.</p>
     */
    boolean canSelect(Actor actor, World world, BlockVector3 position);

    static void set(@Nullable SelectionGuard guard) {
        Holder.guard = guard;
    }

    static boolean allows(Actor actor, World world, BlockVector3 position) {
        SelectionGuard guard = Holder.guard;
        return guard == null || guard.canSelect(actor, world, position);
    }

    /**
     * Kept in its own class so the field is not part of the interface's public API.
     */
    final class Holder {
        private static volatile @Nullable SelectionGuard guard;

        private Holder() {
        }
    }
}
