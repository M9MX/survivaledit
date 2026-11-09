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
import com.sk89q.worldedit.extent.AbstractBufferingExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects an edit instead of writing it, and decides at the end whether it may run.
 *
 * <p>Nothing is written while the command runs. The blocks are collected, costed, and paid for in
 * one go; only then are they handed to {@link SurvivalEditQueue}. An edit the player cannot afford
 * takes nothing and builds nothing: there is no queuing and no crafting, the items have to be in
 * their inventory or their ME network's storage when the command runs.</p>
 *
 * <p>WorldEdit's own completion line is held back while the edit is pending, so that this extent can
 * report the edit exactly once, with the real cost.</p>
 */
public class SurvivalEditExtent extends AbstractBufferingExtent {

    private final ServerPlayer player;
    private final World world;
    private final SurvivalEditJob.Kind kind;
    private BlockMap<BaseBlock> blockMap = BlockMap.createForBaseBlock();
    /** Protected blocks this edit would overwrite, with how many positions of each. */
    private final Map<String, Integer> protectedBlocks = new LinkedHashMap<>();

    public SurvivalEditExtent(Extent extent, World world, ServerPlayer player, SurvivalEditJob.Kind kind) {
        super(extent);
        this.world = world;
        this.player = player;
        this.kind = kind;
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 location, B block) throws WorldEditException {
        BaseBlock newBlock = block.toBaseBlock();
        BaseBlock existing = getFullBlock(location);

        // Commands routinely "set" blocks that are already there. Skipping them keeps the edit from
        // being charged for nothing.
        if (existing != null && existing.equals(newBlock)) {
            return false;
        }

        // Only the block already in the world can be protected: an edit may build next to a
        // protected block, never over it. The first write at a position is the one that sees the
        // world, so later writes of the same position are not counted again.
        if (kind == SurvivalEditJob.Kind.PAID && existing != null && blockMap.get(location) == null
            && SurvivalMode.isProtected(existing.getBlockType())) {
            protectedBlocks.merge(existing.getBlockType().id(), 1, Integer::sum);
        }

        blockMap.put(location, newBlock);
        return true;
    }

    @Override
    protected BaseBlock getBufferedFullBlock(BlockVector3 position) {
        return blockMap.get(position);
    }

    @Override
    protected Operation commitBefore() {
        // This is called while the commit operation tree is built, which is before the extents
        // above this one (batching, reordering) flush their buffers into it. The hand-off therefore
        // has to happen when the operation runs, not now.
        return new Operation() {
            @Override
            public Operation resume(RunContext run) {
                // The edit is complete and costed, so WorldEdit can talk to the player again once
                // the report below is out.
                SurvivalPendingEdit.begin(player.getUUID());
                try {
                    if (blockMap.isEmpty()) {
                        return null;
                    }

                    // Protected blocks are checked before the edit is costed, so an edit that would
                    // destroy one takes nothing and writes nothing. The whole edit is refused
                    // rather than the offending positions being skipped: half a build is worse
                    // than none.
                    if (!protectedBlocks.isEmpty()) {
                        SurvivalMessages.error(player, "Edit cancelled: it would overwrite "
                            + describeProtected() + ". Those blocks are protected in survival;"
                            + " see survival.protected-blocks in worldedit.properties.");
                        protectedBlocks.clear();
                        blockMap.clear();
                        return null;
                    }

                    // The queue owns the buffer from here, and a later flush of the same session
                    // starts a new one.
                    BlockMap<BaseBlock> pending = blockMap;
                    blockMap = BlockMap.createForBaseBlock();

                    int size = pending.size();
                    String limit = SurvivalEditQueue.reject(player, size, kind);
                    if (limit != null) {
                        SurvivalMessages.error(player, "Edit cancelled: " + limit + ".");
                        return null;
                    }

                    ServerLevel level = level();
                    if (level == null) {
                        SurvivalMessages.error(player, "Edit cancelled: that world is not available.");
                        return null;
                    }

                    if (kind == SurvivalEditJob.Kind.FREE) {
                        // Nothing is charged and WorldEdit reports the completion itself, so the
                        // edit only has to be applied a chunk at a time.
                        SurvivalEditQueue.enqueue(player, level, pending, kind, Map.of());
                        return null;
                    }

                    Map<Item, Long> cost = ItemLedger.costOf(pending);
                    SurvivalPayment.Result payment = SurvivalPayment.charge(player, cost);
                    if (!payment.paid()) {
                        SurvivalMessages.error(player, "Edit cancelled: " + payment.reason() + ".");
                        return null;
                    }

                    SurvivalEditQueue.enqueue(player, level, pending, kind, cost);
                    SurvivalMessages.info(player, success(size, cost));
                    return null;
                } finally {
                    SurvivalPendingEdit.end(player.getUUID());
                }
            }

            @Override
            public void cancel() {
                blockMap.clear();
                protectedBlocks.clear();
            }
        };
    }

    /** Names the protected blocks in the way, so the player can see what stopped the edit. */
    private String describeProtected() {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : protectedBlocks.entrySet()) {
            if (shown == 4) {
                out.append(" and ").append(protectedBlocks.size() - shown).append(" more");
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            out.append(entry.getKey());
            if (entry.getValue() > 1) {
                out.append(" x").append(entry.getValue());
            }
            shown++;
        }
        return out.toString();
    }

    private ServerLevel level() {
        if (world != null) {
            try {
                return NeoForgeAdapter.adapt(world);
            } catch (Throwable ignored) {
                // Fall through to the player's own world.
            }
        }
        return player.level() instanceof ServerLevel level ? level : null;
    }

    private static String success(int size, Map<Item, Long> cost) {
        String used = cost.isEmpty() ? "" : ", used " + ItemLedger.describe(cost);
        return "Operation completed (" + size + " blocks affected)" + used + ".";
    }
}
