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
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

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

    public SurvivalEditExtent(Extent extent, World world, ServerPlayer player, SurvivalEditJob.Kind kind) {
        super(extent);
        this.world = world;
        this.player = player;
        this.kind = kind;
        if (kind == SurvivalEditJob.Kind.PAID) {
            SurvivalPendingEdit.begin(player.getUUID());
        }
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
                // The edit is complete and costed, so WorldEdit can talk to the player again.
                SurvivalPendingEdit.end(player.getUUID());

                if (blockMap.isEmpty()) {
                    return null;
                }

                // The queue owns the buffer from here, and a later flush of the same session
                // starts a new one.
                BlockMap<BaseBlock> pending = blockMap;
                blockMap = BlockMap.createForBaseBlock();

                int size = pending.keySet().size();
                Map<Item, Long> cost = ItemLedger.costOf(pending);

                String limit = SurvivalEditQueue.reject(player, pending, kind);
                if (limit != null) {
                    SurvivalMessages.error(player, "Edit cancelled: " + limit + ".");
                    return null;
                }

                if (kind == SurvivalEditJob.Kind.FREE) {
                    // Nothing is charged and WorldEdit reports the completion itself, so the edit
                    // only has to be applied a chunk at a time.
                    SurvivalEditQueue.enqueue(player, getExtent(), world, pending, kind);
                    return null;
                }

                if (kind == SurvivalEditJob.Kind.UNDO) {
                    SurvivalEditQueue.enqueue(player, getExtent(), world, pending, kind);
                    return null;
                }

                SurvivalPayment.Result payment = SurvivalPayment.charge(player, cost);
                if (!payment.paid()) {
                    SurvivalMessages.error(player, "Edit cancelled: " + payment.reason() + ".");
                    return null;
                }

                SurvivalEditQueue.enqueue(player, getExtent(), world, pending, kind);
                SurvivalMessages.info(player, success(size, cost));
                return null;
            }

            @Override
            public void cancel() {
                blockMap.clear();
            }
        };
    }

    private static String success(int size, Map<Item, Long> cost) {
        String used = cost.isEmpty() ? "" : ", used " + ItemLedger.describe(cost);
        return "Operation completed (" + size + " blocks affected)" + used + ".";
    }
}
