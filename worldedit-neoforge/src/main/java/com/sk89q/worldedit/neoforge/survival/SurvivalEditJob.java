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
    final Map<Item, Map<String, Long>> chargedSources;
    private final Map<Item, Map<String, Long>> claimSourceBudget;
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
    final Map<Item, Map<String, Long>> refundSources = new LinkedHashMap<>();

    private SurvivalEditJob(ServerPlayer player, Kind kind, ServerLevel level,
                            BlockVector3[] positions, BaseBlock[] targets,
                            Map<Item, Long> charged,
                            Map<Item, Map<String, Long>> chargedSources,
                            @Nullable SurvivalEditRecord record) {
        this.player = player;
        this.kind = kind;
        this.level = level;
        this.dimension = level.dimension().location().toString();
        this.positions = positions;
        this.targets = targets;
        this.charged = charged;
        this.chargedSources = chargedSources;
        this.claimSourceBudget = copySources(chargedSources);
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
                                BlockMap<BaseBlock> blocks, Map<Item, Long> charged,
                                Map<Item, Map<String, Long>> chargedSources) {
        BlockVector3[] positions = blocks.keySet().toArray(new BlockVector3[0]);
        sortByChunk(positions);
        BaseBlock[] targets = new BaseBlock[positions.length];
        for (int i = 0; i < positions.length; i++) {
            targets[i] = blocks.get(positions[i]);
        }
        return new SurvivalEditJob(player, kind, level, positions, targets, charged,
            chargedSources, null);
    }

    /**
     * Creates a job that replays a stored edit, forwards or backwards.
     */
    static SurvivalEditJob replay(ServerPlayer player, Kind kind, ServerLevel level,
                                  SurvivalEditRecord record, BlockVector3[] positions, BaseBlock[] targets,
                                  Map<Item, Long> charged,
                                  Map<Item, Map<String, Long>> chargedSources) {
        return new SurvivalEditJob(player, kind, level, positions.clone(), targets.clone(), charged,
            chargedSources, record);
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
            .comparingInt((BlockVector3 position) -> position.x() >> 4)
            .thenComparingInt(position -> position.z() >> 4)
            .thenComparingInt(BlockVector3::y)
            .thenComparingInt(BlockVector3::x));
    }

    BlockPos blockPos(int index) {
        BlockVector3 position = positions[index];
        return new BlockPos(position.x(), position.y(), position.z());
    }

    /**
     * Spends the items of one paid block from the budget, when the job is allowed to own it.
     *
     * <p>A block can be paid with several items, so the whole cost has to fit. A copycat whose
     * material is not left in the budget is not claimed at all, rather than half claimed.</p>
     *
     * @return the items the block was bought with, or {@code null} when this job did not pay for it
     */
    @Nullable
    Map<Item, Long> claimBudgetFor(BaseBlock target) {
        if (claimBudget.isEmpty()) {
            return null;
        }
        Map<Item, Long> needed = ItemLedger.costOf(target);
        if (needed.isEmpty()) {
            return null;
        }
        for (Map.Entry<Item, Long> entry : needed.entrySet()) {
            if (claimBudget.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                return null;
            }
        }
        for (Map.Entry<Item, Long> entry : needed.entrySet()) {
            claimBudget.put(entry.getKey(), claimBudget.get(entry.getKey()) - entry.getValue());
        }
        return needed;
    }

    /** Where each item of a claimed block came from. */
    Map<Item, String> claimSourcesFor(Map<Item, Long> items) {
        Map<Item, String> sources = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : items.entrySet()) {
            String source = claimSourceFor(entry.getKey(), entry.getValue());
            if (source != null) {
                sources.put(entry.getKey(), source);
            }
        }
        return sources;
    }

    @Nullable
    private String claimSourceFor(Item item, long units) {
        Map<String, Long> sources = claimSourceBudget.get(item);
        if (sources == null) {
            return null;
        }
        String first = null;
        java.util.Iterator<Map.Entry<String, Long>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext() && units > 0) {
            Map.Entry<String, Long> entry = iterator.next();
            long taken = Math.min(entry.getValue(), units);
            if (taken <= 0) {
                continue;
            }
            if (first == null) {
                first = entry.getKey();
            }
            units -= taken;
            entry.setValue(entry.getValue() - taken);
            if (entry.getValue() <= 0) {
                iterator.remove();
            }
        }
        return first;
    }

    private static Map<Item, Map<String, Long>> copySources(
            Map<Item, Map<String, Long>> sources) {
        Map<Item, Map<String, Long>> copy = new LinkedHashMap<>();
        for (Map.Entry<Item, Map<String, Long>> entry : sources.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Pays a bought block back to whoever paid for it, because it has just left the world.
     *
     * <p>Only the player's own blocks are added to this job's refund; a block another player bought
     * is handed straight back to them, so replacing someone else's work cannot move their items.</p>
     */
    void refund(SurvivalClaims.Claim claim) {
        if (claim.owner.equals(player.getUUID())) {
            for (SurvivalClaims.Claim.Entry entry : claim.items) {
                refund.merge(entry.item, entry.count, Long::sum);
                refundSources.computeIfAbsent(entry.item, ignored -> new LinkedHashMap<>())
                    .merge(entry.source, entry.count, Long::sum);
            }
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
        Map<String, Map<Item, Long>> bySource = new LinkedHashMap<>();
        for (SurvivalClaims.Claim.Entry entry : claim.items) {
            bySource.computeIfAbsent(entry.source, ignored -> new LinkedHashMap<>())
                .merge(entry.item, entry.count, Long::sum);
        }
        for (Map.Entry<String, Map<Item, Long>> entry : bySource.entrySet()) {
            SurvivalStocks.storeToSource(owner, entry.getKey(), entry.getValue());
        }
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
                SurvivalMessages.editInfo(player, size(), "Left " + skippedProtected
                    + " protected block" + (skippedProtected == 1 ? "" : "s")
                    + " alone (survival.protected-blocks).");
            }

            if (!refund.isEmpty()) {
                storeRefundSources(refund, refundSources);
                SurvivalMessages.editInfo(player, size(), kind == Kind.UNDO
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
                chargedSources,
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
            ItemLedger.addCost(placed, block);
        }
        Map<Item, Long> back = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : charged.entrySet()) {
            long unused = entry.getValue() - placed.getOrDefault(entry.getKey(), 0L);
            if (unused > 0) {
                back.put(entry.getKey(), unused);
            }
        }
        if (!back.isEmpty()) {
            Map<Item, Map<String, Long>> backSources = new LinkedHashMap<>();
            for (Map.Entry<Item, Long> entry : back.entrySet()) {
                long remaining = entry.getValue();
                Map<String, Long> sources = chargedSources.get(entry.getKey());
                if (sources != null) {
                    for (Map.Entry<String, Long> source : sources.entrySet()) {
                        if (remaining <= 0) {
                            break;
                        }
                        long amount = Math.min(source.getValue(), remaining);
                        if (amount > 0) {
                            backSources.computeIfAbsent(entry.getKey(),
                                ignored -> new LinkedHashMap<>())
                                .put(source.getKey(), amount);
                            remaining -= amount;
                        }
                    }
                }
                if (remaining > 0) {
                    backSources.computeIfAbsent(entry.getKey(),
                        ignored -> new LinkedHashMap<>())
                        .merge("inventory", remaining, Long::sum);
                }
            }
            storeRefundSources(back, backSources);
            SurvivalMessages.editInfo(player, size(),
                "Returned " + ItemLedger.describe(back) + " for blocks that were not placed.");
        }
    }

    private void storeRefundSources(Map<Item, Long> amounts,
                                    Map<Item, Map<String, Long>> sources) {
        Map<Item, Long> bySource = new LinkedHashMap<>();
        for (Map.Entry<Item, Map<String, Long>> entry : sources.entrySet()) {
            Map<Item, Long> one = new LinkedHashMap<>();
            one.put(entry.getKey(), amounts.getOrDefault(entry.getKey(), 0L));
            long sent = 0;
            for (Map.Entry<String, Long> source : entry.getValue().entrySet()) {
                long amount = source.getValue();
                if (amount <= 0) {
                    continue;
                }
                Map<Item, Long> packageAmount = new LinkedHashMap<>();
                packageAmount.put(entry.getKey(), amount);
                SurvivalStocks.storeToSource(player, source.getKey(), packageAmount);
                sent += amount;
            }
            long missing = amounts.getOrDefault(entry.getKey(), 0L) - sent;
            if (missing > 0) {
                bySource.merge(entry.getKey(), missing, Long::sum);
            }
        }
        if (!bySource.isEmpty()) {
            SurvivalStocks.store(player, bySource);
        }
    }
}
