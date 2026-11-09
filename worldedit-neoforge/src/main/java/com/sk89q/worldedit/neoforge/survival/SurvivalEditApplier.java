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
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.neoforge.internal.ExtendedChunk;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes the blocks of a job into the world, one chunk at a time.
 *
 * <p>Only actual changes are written and recorded: a position the world already holds is stepped
 * over. The old state of every block that is written is kept, both for the history file and for the
 * ownership ledger, which decides whether a block that leaves the world is refunded.</p>
 */
final class SurvivalEditApplier {

    private static final Logger LOGGER = LogManager.getLogger();

    /** How many ticks a position may fail before it is skipped instead of blocking the job. */
    private static final int MAX_RETRIES = 200;

    private static final SideEffectSet SIDE_EFFECTS = SideEffectSet.defaults()
        .with(SideEffect.UPDATE, SideEffect.State.ON)
        .with(SideEffect.NEIGHBORS, SideEffect.State.ON)
        .with(SideEffect.NETWORK, SideEffect.State.ON)
        .with(SideEffect.LIGHTING, SideEffect.State.ON);
    private static final int UPDATE_FLAGS = 1 | 2;

    private SurvivalEditApplier() {
    }

    /**
     * Applies as many blocks as the budget allows.
     *
     * @return the number of blocks written
     */
    static int apply(SurvivalEditJob job, int budget) {
        int written = 0;
        while (written < budget && !job.isDone()) {
            int index = job.cursor;
            BlockVector3 position = job.positions[index];
            BlockPos pos = job.blockPos(index);
            int chunkX = position.getX() >> 4;
            int chunkZ = position.getZ() >> 4;

            if (chunkX != job.chunkX || chunkZ != job.chunkZ) {
                long now = System.currentTimeMillis();
                if (now < job.nextChunkAt) {
                    // The previous chunk has not been left alone long enough yet.
                    return written;
                }
                job.chunkX = chunkX;
                job.chunkZ = chunkZ;
                job.nextChunkAt = now + SurvivalMode.perChunkDelayMs();
            }

            BlockState target = job.targetStates[index];
            if (target == null) {
                // A block from a mod that is no longer installed. There is nothing to write, and
                // the charge for it is returned when the job finishes.
                job.cursor++;
                job.retries = 0;
                continue;
            }

            ExtendedChunk chunk = getChunk(job.level, chunkX, chunkZ);
            if (chunk == null) {
                // The chunk is not available right now. Keep the position and try again next tick,
                // but do not let a permanently missing chunk stall the edit forever.
                if (++job.retries > MAX_RETRIES) {
                    LOGGER.warn("SurvivalEdit: skipping {} blocks in an unavailable chunk at {}, {}",
                        1, chunkX, chunkZ);
                    job.cursor++;
                    job.retries = 0;
                }
                return written;
            }
            job.retries = 0;

            BlockState oldState = job.level.getBlockState(pos);
            if (oldState.equals(target)) {
                job.cursor++;
                continue;
            }

            // A protected block has been placed here since the edit was recorded. Undo and redo
            // leave it alone rather than destroy something the player cannot get back; the charge
            // for the block they then do not place is returned when the job finishes.
            if ((job.kind == SurvivalEditJob.Kind.UNDO || job.kind == SurvivalEditJob.Kind.REDO)
                && SurvivalMode.isProtected(NeoForgeAdapter.adapt(oldState).getBlockType())) {
                job.cursor++;
                job.skippedProtected++;
                continue;
            }

            BlockState replaced = chunk.setBlockState(pos, target, false,
                SIDE_EFFECTS.shouldApply(SideEffect.UPDATE));
            BlockState previous = replaced != null ? replaced : oldState;
            if (replaced != null) {
                applySideEffects(job.level, chunk, pos, replaced, target);
                updateClaims(job, index, pos, replaced, target);
            }

            job.previousStates.add(toBaseBlock(previous));
            job.nextStates.add(job.targets[index]);
            job.changedPositions.add(position);
            job.cursor++;
            written++;
        }
        return written;
    }

    /**
     * Moves ownership of the block that just left the world, and of the block that took its place.
     *
     * <p>Nothing here trusts the command: a block only pays its item back when the world still held
     * the exact block that was bought, and the claim is spent the moment it is paid, so no sequence
     * of edits and undos can refund the same block twice. The block that arrives either pays for
     * itself out of the job's budget, or, when it is a block whose refund was already handed out, it
     * has been paid for again by the command that restored it.</p>
     */
    private static void updateClaims(SurvivalEditJob job, int index, BlockPos pos,
                                     BlockState previous, BlockState target) {
        SurvivalClaims claims = SurvivalClaims.get();
        if (claims == null) {
            return;
        }
        String dimension = job.dimension;
        long key = pos.asLong();
        int targetId = Block.getId(target);

        SurvivalClaims.Claim leaving = claims.takeRefund(dimension, key, Block.getId(previous));
        if (leaving != null) {
            job.refund(leaving);
        }

        SurvivalClaims.Claim returning = claims.unspend(dimension, key, targetId);
        if (returning != null) {
            claims.claim(dimension, key, targetId, job.player.getUUID(), returning.item);
            return;
        }

        Item item = job.claimBudgetFor(job.targets[index]);
        if (item != null) {
            claims.claim(dimension, key, targetId, job.player.getUUID(), item);
        }
    }

    private static BaseBlock toBaseBlock(BlockState state) {
        return NeoForgeAdapter.adapt(state).toBaseBlock();
    }

    private static ExtendedChunk getChunk(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return (ExtendedChunk) level.getChunk(chunkX, chunkZ);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applySideEffects(ServerLevel world, ExtendedChunk chunk, BlockPos pos,
                                         BlockState oldState, BlockState newState) {
        if (SIDE_EFFECTS.shouldApply(SideEffect.UPDATE)) {
            newState.onPlace(world, pos, oldState, false);
        }
        if (SIDE_EFFECTS.shouldApply(SideEffect.NEIGHBORS)) {
            if (SIDE_EFFECTS.shouldApply(SideEffect.EVENTS)) {
                world.updateNeighborsAt(pos, oldState.getBlock());
            } else {
                Block block = oldState.getBlock();
                world.neighborChanged(pos.west(), block, pos);
                world.neighborChanged(pos.east(), block, pos);
                world.neighborChanged(pos.below(), block, pos);
                world.neighborChanged(pos.above(), block, pos);
                world.neighborChanged(pos.north(), block, pos);
                world.neighborChanged(pos.south(), block, pos);
            }
            if (newState.hasAnalogOutputSignal()) {
                world.updateNeighbourForOutputSignal(pos, newState.getBlock());
            }
        }
        if (SIDE_EFFECTS.getState(SideEffect.LIGHTING) == SideEffect.State.ON) {
            world.getChunkSource().getLightEngine().checkBlock(pos);
        }
        world.getChunkSource().blockChanged(pos);
        if (SIDE_EFFECTS.shouldApply(SideEffect.NETWORK) || SIDE_EFFECTS.shouldApply(SideEffect.ENTITY_AI)) {
            world.sendBlockUpdated(pos, oldState, newState, UPDATE_FLAGS);
        }
    }
}
