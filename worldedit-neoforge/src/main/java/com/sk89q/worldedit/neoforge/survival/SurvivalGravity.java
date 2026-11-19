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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Puts falling blocks on the ground before an edit is costed and applied.
 *
 * <p>Vanilla pastes sand and lets it fall a tick later, which leaves the edit holding positions the
 * sand has already left: the player is charged for blocks that are no longer there, and undo clears
 * the air above the pile instead of the pile. Settling the blocks first means the edit, its cost
 * and its history are all about the blocks that will actually stand in the world, and the paste is
 * finished when it is applied rather than when the last grain lands.</p>
 *
 * <p>A block falls as vanilla would let it fall: straight down through anything that can be
 * replaced, until it meets something solid or the bottom of the world.</p>
 */
final class SurvivalGravity {

    private SurvivalGravity() {
    }

    /**
     * Moves every falling block of an edit to where it would come to rest.
     *
     * @return a new map, with the falling blocks at their landing positions
     */
    static BlockMap<BaseBlock> settle(ServerLevel level, BlockMap<BaseBlock> blocks) {
        BlockMap<BaseBlock> settled = BlockMap.createForBaseBlock();
        List<BlockVector3> falling = new ArrayList<>();
        for (BlockVector3 position : blocks.keySet()) {
            BaseBlock block = blocks.get(position);
            if (block == null) {
                continue;
            }
            if (falls(block)) {
                falling.add(position);
            } else {
                settled.put(position, block);
            }
        }

        // Bottom-up, so a column of sand stacks on itself instead of every block landing on the
        // floor of the column.
        falling.sort(Comparator
            .comparingInt(BlockVector3::y)
            .thenComparingInt(BlockVector3::x)
            .thenComparingInt(BlockVector3::z));

        for (BlockVector3 origin : falling) {
            BaseBlock block = blocks.get(origin);
            if (block != null) {
                settled.put(landing(level, settled, origin), block);
            }
        }
        return settled;
    }

    private static boolean falls(BaseBlock block) {
        BlockState state = SurvivalEditJob.nativeState(block);
        return state != null && state.getBlock() instanceof FallingBlock;
    }

    private static BlockVector3 landing(ServerLevel level, BlockMap<BaseBlock> settled,
                                        BlockVector3 origin) {
        int x = origin.x();
        int z = origin.z();
        int y = origin.y();
        int floor = level.getMinBuildHeight();
        while (y > floor && passable(level, settled, x, y - 1, z)) {
            y--;
        }
        return BlockVector3.at(x, y, z);
    }

    /** Whether a falling block moves through this position on its way down. */
    private static boolean passable(ServerLevel level, BlockMap<BaseBlock> settled,
                                    int x, int y, int z) {
        BaseBlock pending = settled.get(BlockVector3.at(x, y, z));
        if (pending != null) {
            // The edit's own blocks are part of the world the sand falls through.
            BlockState state = SurvivalEditJob.nativeState(pending);
            return state != null && passable(state);
        }
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return passable(state);
    }

    private static boolean passable(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock || state.canBeReplaced();
    }
}
