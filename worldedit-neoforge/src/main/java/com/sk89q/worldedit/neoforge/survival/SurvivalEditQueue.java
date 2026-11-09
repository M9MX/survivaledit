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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Applies survival edits gradually, one slice per server tick.
 *
 * <p>Jobs run in the order they were queued, and the per-tick budget is shared between all of them,
 * so ten players pasting at once cost the same as one: the work is spread out instead of stacking
 * up. Because an edit is fully paid for before it is queued, a job that could not be paid for never
 * reaches the queue. Keeping the order also means an undo or a redo issued straight after an edit
 * sees the blocks that edit placed.</p>
 *
 * <p>A job that is dropped before it is finished, on shutdown for example, is finished anyway: it
 * stores what it did and returns the items for the blocks it never placed.</p>
 */
public final class SurvivalEditQueue {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Deque<SurvivalEditJob> JOBS = new ArrayDeque<>();

    private SurvivalEditQueue() {
    }

    /**
     * Whether the player still has an edit waiting to be applied.
     */
    public static boolean isBusy(ServerPlayer player) {
        for (SurvivalEditJob job : JOBS) {
            if (job.player.getUUID().equals(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks the reasons an edit can be refused before it is paid for.
     *
     * @return {@code null} when the edit may run, otherwise why it may not
     */
    static @Nullable String reject(ServerPlayer player, int blocks, SurvivalEditJob.Kind kind) {
        if (blocks == 0) {
            return null;
        }
        if (kind == SurvivalEditJob.Kind.FREE) {
            // A free edit is not drawn from storage, so the survival block cap does not apply.
            return null;
        }
        if (!player.isAlive()) {
            return "edits are not allowed in your current state";
        }
        int max = SurvivalMode.maxBlocksPerEdit();
        if (max > 0 && blocks > max) {
            return "this edit changes " + blocks + " blocks, which is above the limit of " + max;
        }
        return null;
    }

    /**
     * Queues an edit that has already been paid for.
     */
    static void enqueue(ServerPlayer player, ServerLevel level, BlockMap<BaseBlock> blocks,
                        SurvivalEditJob.Kind kind, Map<Item, Long> charged) {
        if (blocks.isEmpty()) {
            return;
        }
        JOBS.addLast(SurvivalEditJob.edit(player, kind, level, blocks, charged));
    }

    /**
     * Queues the undo or redo of a stored edit.
     */
    static void enqueueReplay(ServerPlayer player, ServerLevel level, SurvivalEditJob.Kind kind,
                              SurvivalEditRecord record, BlockVector3[] positions, BaseBlock[] targets,
                              Map<Item, Long> charged) {
        if (positions.length == 0) {
            return;
        }
        JOBS.addLast(SurvivalEditJob.replay(player, kind, level, record, positions, targets, charged));
    }

    /**
     * Advances all queued edits. Called once per server tick.
     */
    public static void tick(MinecraftServer server) {
        int budget = SurvivalMode.blocksPerTick();
        int jobs = JOBS.size();
        for (int i = 0; i < jobs && budget > 0; i++) {
            SurvivalEditJob job = JOBS.pollFirst();
            if (job == null) {
                return;
            }

            int used;
            try {
                used = job.tick(budget);
            } catch (Throwable t) {
                LOGGER.error("SurvivalEdit: an edit failed for {}", job.player.getName().getString(), t);
                SurvivalMessages.error(job.player, "The edit stopped because of an error. What was applied so far is kept.");
                budget = 0;
                finish(job);
                continue;
            }

            budget -= used;
            if (job.isDone()) {
                finish(job);
            } else {
                JOBS.addLast(job);
            }
        }
    }

    /**
     * Stops any pending work, storing what was applied and returning the rest of the payment.
     */
    public static void clear() {
        List<SurvivalEditJob> jobs = new ArrayList<>(JOBS);
        JOBS.clear();
        for (SurvivalEditJob job : jobs) {
            finish(job);
        }
    }

    private static void finish(SurvivalEditJob job) {
        try {
            job.finish();
        } catch (Throwable t) {
            LOGGER.error("SurvivalEdit: could not finish an edit for {}", job.player.getName().getString(), t);
        }
    }
}
