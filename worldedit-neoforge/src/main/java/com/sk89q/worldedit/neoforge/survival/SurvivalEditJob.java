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

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.internal.util.RegionOptimizedVectorSorter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single edit that is paid for and then written into the world a chunk at a time.
 *
 * <p>Nothing reaches the world until the whole edit has been paid for, so a player who cannot afford
 * an edit never ends up with a half-applied one. An undo is the exception: it was already paid for,
 * and the blocks it removes are handed back to the payer as it runs.</p>
 *
 * <p>The edit is applied the way FastAsyncWorldEdit does it in spirit: positions are grouped by
 * chunk, and a job only ever works on one chunk at a time, within a small slice of each server tick.
 * A large edit therefore spreads over several ticks and never blocks a single one, and because a
 * whole chunk's changes land together the client and the light engine see them in one go instead of
 * being nudged block by block across a whole second.</p>
 */
final class SurvivalEditJob {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * How long an edit may hold the server thread per tick. It is a time budget rather than a block
     * count because one block can cost much more than another (block entities, light updates), and a
     * fixed count would still be able to stall a tick.
     */
    private static final long TICK_BUDGET_NANOS = 5_000_000L;

    enum Kind {
        /**
         * A new edit: every placed block is paid for and replaced blocks are voided.
         */
        PAID,
        /**
         * Replaying history backwards: free, and removed blocks are returned to the player.
         */
        UNDO,
        /**
         * An edit that costs nothing, such as one made in creative mode. It is still applied a
         * chunk at a time, because no edit should ever stall a server tick.
         */
        FREE
    }

    /**
     * The positions of one chunk, in the order they are written.
     */
    private static final class ChunkSlice {
        private final List<BlockVector3> positions;

        private ChunkSlice(List<BlockVector3> positions) {
            this.positions = positions;
        }
    }

    private final ServerPlayer player;
    private final Extent target;
    private final World world;
    private final Kind kind;
    private final List<ChunkSlice> slices;
    private final BlockMap<BaseBlock> blocks;
    private final Map<Item, Long> refunds = new LinkedHashMap<>();

    private int sliceIndex;
    private int offsetInSlice;
    private boolean finished;

    SurvivalEditJob(ServerPlayer player, Extent target, World world, BlockMap<BaseBlock> blocks, Kind kind) {
        this.player = player;
        this.target = target;
        this.world = world;
        this.kind = kind;
        this.blocks = blocks;

        List<BlockVector3> positions = new ArrayList<>(blocks.keySet());
        RegionOptimizedVectorSorter.sort(positions);

        // Region order already walks the edit chunk by chunk, so grouping in that order keeps both
        // the chunk order sensible and the order inside a chunk cache friendly.
        Map<Long, List<BlockVector3>> byChunk = new LinkedHashMap<>();
        for (BlockVector3 position : positions) {
            byChunk.computeIfAbsent(chunkKey(position), key -> new ArrayList<>()).add(position);
        }
        this.slices = new ArrayList<>(byChunk.size());
        for (List<BlockVector3> chunkPositions : byChunk.values()) {
            this.slices.add(new ChunkSlice(chunkPositions));
        }
    }

    private static long chunkKey(BlockVector3 position) {
        return ((long) (position.x() >> 4) << 32) | ((position.z() >> 4) & 0xFFFFFFFFL);
    }

    /**
     * Advances the job by one tick.
     *
     * @return whether the job is finished and can be removed
     */
    boolean tick(int blocksPerTick) {
        if (finished) {
            return true;
        }

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int applied = 0;

        while (sliceIndex < slices.size()) {
            if (applied >= blocksPerTick || System.nanoTime() >= deadline) {
                return false;
            }

            ChunkSlice slice = slices.get(sliceIndex);
            while (offsetInSlice < slice.positions.size()
                && applied < blocksPerTick
                && System.nanoTime() < deadline) {
                apply(slice.positions.get(offsetInSlice++));
                applied++;
            }

            if (offsetInSlice < slice.positions.size()) {
                // Out of budget halfway through a chunk. The next tick picks up right here so the
                // chunk is still finished before the job moves on.
                return false;
            }

            sliceIndex++;
            offsetInSlice = 0;
        }

        finish();
        return true;
    }

    private void apply(BlockVector3 position) {
        BaseBlock block = blocks.get(position);
        if (block == null) {
            return;
        }

        BaseBlock previous = target.getFullBlock(position);
        if (kind == Kind.UNDO && previous != null && !previous.equals(block)) {
            // The block that is about to be replaced came out of the world during a paid edit, so
            // it goes back to whatever paid for it.
            Item item = ItemLedger.itemFor(previous);
            if (item != null) {
                refunds.merge(item, 1L, Long::sum);
            }
        }

        try {
            target.setBlock(position, block);
            // These blocks are written outside the edit session, so nothing else sends the change
            // to clients or updates lighting and neighbours. Do what the session would have done,
            // or the edit would only show up after a chunk reload.
            if (previous != null) {
                world.applySideEffects(position, previous.toImmutableState(), SideEffectSet.defaults());
            }
        } catch (WorldEditException e) {
            LOGGER.warn("SurvivalEdit failed to apply a block at {}", position, e);
        }
    }

    private void finish() {
        finished = true;
        int changed = blocks.size();
        blocks.clear();

        if (kind == Kind.UNDO && !refunds.isEmpty()) {
            SurvivalStocks.store(player, refunds);
            refunds.clear();
        }

        LOGGER.info("SurvivalEdit: applied {} blocks for {} in {} chunks", changed,
            player.getName().getString(), slices.size());
    }

    /**
     * Abandons the job without touching the world.
     */
    void cancel() {
        finished = true;
        blocks.clear();
        refunds.clear();
    }

    boolean belongsTo(ServerPlayer other) {
        return player.getUUID().equals(other.getUUID());
    }

    boolean isOnline() {
        return !player.hasDisconnected();
    }
}
