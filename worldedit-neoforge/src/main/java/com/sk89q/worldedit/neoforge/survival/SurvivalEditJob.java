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

import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A single edit that is being applied to the world over several server ticks.
 *
 * <p>The job owns the whole edit: the blocks it writes, the old states it meets as it goes, and what
 * it was charged. When the job is finished, by completing or because the server is stopping, it
 * returns whatever was paid for but not placed, pays back the blocks it replaced that came from
 * storage, and stores itself in the player's history.</p>
 *
 * <p>Positions are sorted into chunk order once, when the job is created, so a tick never re-sorts
 * the edit. The arrays are parallel: {@code positions[i]} is written with {@code targets[i]}.</p>
 */
public final class SurvivalEditJob {

    private static final Logger LOGGER = LogManager.getLogger();

    public enum Kind {
        /** Bought from the player's storage. */
        PAID,
        /** Not charged, for example a creative-mode edit. */
        FREE,
        /** Restores the blocks an earlier edit replaced. */
        UNDO,
        /** Re-applies an edit that was undone. */
        REDO
    }

    final ServerPlayer player;
    final Kind kind;
    final ServerLevel level;
    final String dimension;
    final @Nullable SurvivalEditRecord record;

    final BlockVector3[] positions;
    final BaseBlock[] targets;
    final @Nullable BlockState[] targetStates;
    final Map<Item, Long> charged;
    /** Items that are still owed a claim, so no more blocks gain ownership than were paid for. */
    private final Map<Item, Long> claimBudget;

    int cursor;
    int retries;
    /** Positions an undo or redo left alone because the world held a protected block there. */
    int skippedProtected;
    int chunkX = Integer.MIN_VALUE;
    int chunkZ = Integer.MIN_VALUE;
    long nextChunkAt;

    final List<BlockVector3> changedPositions = new ArrayList<>();
    final List<BaseBlock> previousStates = new ArrayList<>();
    final List<BaseBlock> nextStates = new ArrayList<>();
    final Map<Item, Long> refund = new LinkedHashMap<>();

    private SurvivalEditJob(ServerPlayer player, Kind kind, ServerLevel level,
                            BlockVector3[] positions, BaseBlock[] targets,
                            Map<Item, Long> charged, @Nullable SurvivalEditRecord record) {
        this.player = player;
        this.kind = kind;
        this.level = level;
        this.dimension = level.dimension().location().toString();
        this.positions = positions;
        this.targets = targets;
        this.charged = charged;
        this.record = record;
        this.targetStates = nativeStates(targets);
        this.claimBudget = new LinkedHashMap<>();
        if (kind == Kind.PAID || kind == Kind.REDO) {
            // Every block this job pays for becomes the player's: it pays that item back when it
            // leaves the world. A free or undo edit pays for nothing, so it claims nothing.
            this.claimBudget.putAll(charged);
        }
    }

    /**
     * Creates a job for an edit that came from a command.
     */
    static SurvivalEditJob edit(ServerPlayer player, Kind kind, ServerLevel level,
                                BlockMap<BaseBlock> blocks, Map<Item, Long> charged) {
        BlockVector3[] positions = blocks.keySet().toArray(new BlockVector3[0]);
        sortByChunk(positions);
        BaseBlock[] targets = new BaseBlock[positions.length];
        for (int i = 0; i < positions.length; i++) {
            targets[i] = blocks.get(positions[i]);
        }
        return new SurvivalEditJob(player, kind, level, positions, targets, charged, null);
    }

    /**
     * Creates a job that replays a stored edit, forwards or backwards.
     */
    static SurvivalEditJob replay(ServerPlayer player, Kind kind, ServerLevel level,
                                  SurvivalEditRecord record, BlockVector3[] positions, BaseBlock[] targets,
                                  Map<Item, Long> charged) {
        return new SurvivalEditJob(player, kind, level, positions.clone(), targets.clone(), charged, record);
    }

    /**
     * Advances the job within the given budget.
     *
     * @return how many blocks were written this call
     */
    int tick(int budget) {
        return SurvivalEditApplier.apply(this, budget);
    }

    boolean isDone() {
        return cursor >= positions.length;
    }

    int size() {
        return positions.length;
    }

    /**
     * Rebuilds the state the world should hold, so the job can apply it without touching the
     * registries again per block.
     */
    private static BlockState[] nativeStates(BaseBlock[] blocks) {
        BlockState[] states = new BlockState[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            states[i] = nativeState(blocks[i]);
        }
        return states;
    }

    @Nullable
    static BlockState nativeState(BaseBlock block) {
        try {
            com.sk89q.worldedit.world.block.BlockState weState = block.toImmutableState();
            int stateId = BlockStateIdAccess.getBlockStateId(weState);
            if (BlockStateIdAccess.isValidInternalId(stateId)) {
                return Block.stateById(stateId);
            }
            return NeoForgeAdapter.adapt(weState);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void sortByChunk(BlockVector3[] positions) {
        Arrays.sort(positions, Comparator
            .comparingInt((BlockVector3 position) -> position.getX() >> 4)
            .thenComparingInt(position -> position.getZ() >> 4)
            .thenComparingInt(BlockVector3::getY)
            .thenComparingInt(BlockVector3::getX));
    }

    BlockPos blockPos(int index) {
        BlockVector3 position = positions[index];
        return new BlockPos(position.getX(), position.getY(), position.getZ());
    }

    /**
     * Spends one paid block from the budget, when the job is allowed to own the block it writes.
     *
     * @return the item the block was bought with, or {@code null} when this job did not pay for it
     */
    @Nullable
    Item claimBudgetFor(BaseBlock target) {
        if (claimBudget.isEmpty()) {
            return null;
        }
        Item item = ItemLedger.itemFor(target);
        if (item == null) {
            return null;
        }
        long left = claimBudget.getOrDefault(item, 0L);
        if (left <= 0) {
            return null;
        }
        claimBudget.put(item, left - 1);
        return item;
    }

    /**
     * Pays a bought block back to whoever paid for it, because it has just left the world.
     *
     * <p>Only the player's own blocks are added to this job's refund; a block another player bought
     * is handed straight back to them, so replacing someone else's work cannot move their items.</p>
     */
    void refund(SurvivalClaims.Claim claim) {
        if (claim.owner.equals(player.getUUID())) {
            refund.merge(claim.item, 1L, Long::sum);
            return;
        }
        ServerPlayer owner = player.getServer() == null
            ? null
            : player.getServer().getPlayerList().getPlayer(claim.owner);
        if (owner == null) {
            // The owner is not here to take delivery. The block is voided rather than handed to
            // the player who replaced it.
            LOGGER.debug("SurvivalEdit: voided a refund for {} because its owner is offline",
                claim.owner);
            return;
        }
        SurvivalStocks.store(owner, Map.of(claim.item, 1L));
    }

    /**
     * Returns items for the blocks that were paid for but never placed, and stores the edit in the
     * player's history.
     *
     * <p>Called once, when the job completes or is cancelled. The refund is deliberately computed
     * from what was actually written, not from what was requested: a block that was already in the
     * right state is not charged for, and a block the job never reached is given back.</p>
     */
    void finish() {
        try {
            if (kind != Kind.FREE) {
                refundUnusedCharge();
            }

            if (skippedProtected > 0) {
                SurvivalMessages.info(player, "Left " + skippedProtected + " protected block"
                    + (skippedProtected == 1 ? "" : "s") + " alone (survival.protected-blocks).");
            }

            if (!refund.isEmpty()) {
                SurvivalStocks.store(player, refund);
                SurvivalMessages.info(player, kind == Kind.UNDO
                    ? "Undo finished: returned " + ItemLedger.describe(refund) + "."
                    : "Returned " + ItemLedger.describe(refund) + " for blocks that were replaced.");
            }

            SurvivalClaims claims = SurvivalClaims.get();
            if (claims != null) {
                claims.save();
            }

            if (kind == Kind.UNDO || kind == Kind.REDO) {
                return;
            }

            SurvivalHistory history = SurvivalHistory.get();
            if (history == null || changedPositions.isEmpty()) {
                return;
            }
            history.record(player, dimension, kind == Kind.PAID, charged,
                changedPositions, previousStates, nextStates);
        } catch (Throwable t) {
            LOGGER.error("SurvivalEdit: could not finish an edit for {}", player.getName().getString(), t);
        }
    }

    private void refundUnusedCharge() {
        if (charged.isEmpty()) {
            return;
        }
        Map<Item, Long> placed = new LinkedHashMap<>();
        for (BaseBlock block : nextStates) {
            Item item = ItemLedger.itemFor(block);
            if (item != null) {
                placed.merge(item, 1L, Long::sum);
            }
        }
        Map<Item, Long> back = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : charged.entrySet()) {
            long unused = entry.getValue() - placed.getOrDefault(entry.getKey(), 0L);
            if (unused > 0) {
                back.put(entry.getKey(), unused);
            }
        }
        if (!back.isEmpty()) {
            SurvivalStocks.store(player, back);
            SurvivalMessages.info(player, "Returned " + ItemLedger.describe(back) + " for blocks that were not placed.");
        }
    }
}
