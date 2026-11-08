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

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.collection.BlockMap;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;

/**
 * Applies survival edits gradually, one slice per server tick.
 *
 * <p>Jobs run in the order they were queued. Because an edit is fully paid for before it is
 * enqueued, a job that could not be paid for never reaches the queue. Keeping the order also means
 * an undo or a redo issued straight after an edit sees the blocks that edit placed.</p>
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
            if (job.belongsTo(player)) {
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
    static @Nullable String reject(ServerPlayer player, BlockMap<BaseBlock> blocks,
                                  SurvivalEditJob.Kind kind) {
        if (blocks.isEmpty()) {
            return null;
        }
        if (kind == SurvivalEditJob.Kind.FREE) {
            // A free edit is not drawn from storage, so the survival block cap does not apply.
            return null;
        }
        if (!player.isAlive()) {
            return "edits are not allowed in your current state";
        }
        int max = SurvivalMode.maxQueuedBlocks();
        if (max > 0 && blocks.size() > max) {
            return "this edit changes " + blocks.size() + " blocks, which is above the limit of " + max;
        }
        return null;
    }

    /**
     * Queues an edit that has already been paid for.
     */
    static void enqueue(ServerPlayer player, Extent target, World world, BlockMap<BaseBlock> blocks,
                        SurvivalEditJob.Kind kind) {
        if (blocks.isEmpty()) {
            return;
        }
        JOBS.addLast(new SurvivalEditJob(player, target, world, blocks, kind));
        LOGGER.info("SurvivalEdit: queued {} blocks for {}", blocks.size(), player.getName().getString());
    }

    /**
     * Advances all queued edits. Called once per server tick.
     */
    public static void tick(MinecraftServer server) {
        int budget = SurvivalMode.blocksPerTick();
        int jobs = JOBS.size();
        for (int i = 0; i < jobs; i++) {
            SurvivalEditJob job = JOBS.pollFirst();
            if (job == null) {
                return;
            }
            if (!job.isOnline()) {
                job.cancel();
                continue;
            }
            if (!job.tick(budget)) {
                JOBS.addLast(job);
            }
        }
    }

    /**
     * Stops any pending work, for example when the server shuts down.
     */
    public static void clear() {
        for (SurvivalEditJob job : JOBS) {
            job.cancel();
        }
        JOBS.clear();
    }
}
