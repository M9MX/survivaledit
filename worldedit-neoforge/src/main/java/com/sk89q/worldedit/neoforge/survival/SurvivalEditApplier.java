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
import com.sk89q.worldedit.neoforge.internal.NBTConverter;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
            int chunkX = position.x() >> 4;
            int chunkZ = position.z() >> 4;

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
            BaseBlock targetBlock = job.targets[index];
            // A copycat's material lives in its block entity, so an equal state is not enough to
            // call this a no-op: the data still has to be written.
            if (oldState.equals(target) && targetBlock.getNbt() == null) {
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

            // A copycat or framed block drops its material when it is removed, and this edit pays
            // the material back through the ownership ledger instead. Telling the block that it is
            // being moved by a piston suppresses that drop, so the block cannot be paid out twice.
            // Blocks the player placed by hand are not claimed, so those still drop as usual.
            boolean movedByPiston = dropsWhenRemoved(oldState) && hasLiveClaim(job, pos, oldState);

            // Captured before the write: undoing this has to bring the old copycat material or
            // framed camo back, not just the block state.
            BaseBlock previousFull = fullBlock(job.level, pos, oldState);
            BlockState replaced = chunk.setBlockState(pos, target, movedByPiston,
                SIDE_EFFECTS.shouldApply(SideEffect.UPDATE));
            BlockState previous = replaced != null ? replaced : oldState;
            BaseBlock writtenBlock = targetBlock;
            if (replaced != null || targetBlock.getNbt() != null) {
                writtenBlock = applyBlockEntityData(job.level, pos, targetBlock);
            }
            if (replaced != null) {
                applySideEffects(job.level, chunk, pos, replaced, target);
                updateClaims(job, index, pos, replaced, target);
            } else if (targetBlock.getNbt() != null) {
                // Only the data changed, so the block is unchanged for every neighbour; nothing
                // needs updating except the clients showing it.
                job.level.getChunkSource().blockChanged(pos);
            }

            job.previousStates.add(previousFull);
            job.nextStates.add(writtenBlock);
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
            claims.claim(dimension, key, targetId, job.player.getUUID(),
                returning.amounts(), returning.sources());
            return;
        }

        java.util.Map<Item, Long> items = job.claimBudgetFor(job.targets[index]);
        if (items != null) {
            claims.claim(dimension, key, targetId, job.player.getUUID(), items,
                job.claimSourcesFor(items));
        }
    }

    /** Whether this block is a copycat or framed block, which drops its material on removal. */
    private static boolean dropsWhenRemoved(BlockState state) {
        return SurvivalBlockEntities.isAppearanceEntity(state.getBlock().getClass().getName());
    }

    /** Whether the block standing here was bought and not refunded yet. */
    private static boolean hasLiveClaim(SurvivalEditJob job, BlockPos pos, BlockState state) {
        SurvivalClaims claims = SurvivalClaims.get();
        return claims != null && claims.hasLiveClaim(job.dimension, pos.asLong(), Block.getId(state));
    }

    /**
     * The block at a position, with the block entity data an edit is allowed to carry.
     *
     * <p>Only appearance data is included, so an undo cannot restore an inventory that a paid edit
     * was never allowed to copy.</p>
     */
    private static BaseBlock fullBlock(ServerLevel level, BlockPos pos, BlockState state) {
        BaseBlock base = NeoForgeAdapter.adapt(state).toBaseBlock();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!SurvivalBlockEntities.isCopyable(blockEntity)) {
            return base;
        }
        CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
        if (SurvivalBlockEntities.hasItemContents(tag)) {
            return base;
        }
        return base.toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(tag)));
    }

    /**
     * Loads copied block entity data onto the block that was just written.
     *
     * @return the block as it will be recorded for undo, without data that was refused
     */
    private static BaseBlock applyBlockEntityData(ServerLevel level, BlockPos pos, BaseBlock block) {
        if (block.getNbt() == null) {
            return block;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!SurvivalBlockEntities.isCopyable(blockEntity)) {
            return block.toImmutableState().toBaseBlock();
        }
        CompoundTag tag = NBTConverter.toNative(block.getNbt());
        if (SurvivalBlockEntities.hasItemContents(tag)) {
            return block.toImmutableState().toBaseBlock();
        }
        blockEntity.loadWithComponents(tag, level.registryAccess());
        blockEntity.setChanged();
        return block;
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
            // The same notifications vanilla sends for a hand-placed block: neighbours run their
            // own update logic (a torch falls off, redstone reacts, sand is scheduled) and then
            // recompute their shape, so fences, walls and panes reconnect to the new block.
            world.blockUpdated(pos, newState.getBlock());
            if (!newState.isAir()) {
                newState.updateNeighbourShapes(world, pos, UPDATE_FLAGS);
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
