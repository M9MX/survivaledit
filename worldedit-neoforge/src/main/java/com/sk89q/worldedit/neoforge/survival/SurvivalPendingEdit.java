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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players are in the middle of an edit that SurvivalEdit is going to report on itself.
 *
 * <p>While an edit is pending, WorldEdit's own "operation completed" line is dropped, so the player
 * gets exactly one message about the edit instead of WorldEdit's line plus one from SurvivalEdit.
 * Commands print that line after the edit has been committed, so the silencing has to outlive the
 * commit by a moment; only completion lines are dropped in that window, and SurvivalEdit's own
 * report is allowed through.</p>
 */
public final class SurvivalPendingEdit {

    private static final long TIMEOUT_MS = 30_000;

    /**
     * How long after an edit is committed WorldEdit's completion line is still dropped. It is printed
     * in the same command, milliseconds later.
     */
    private static final long QUIET_AFTER_COMMIT_MS = 2_000;

    private static final String COMPLETION_LINE = "Operation completed";

    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COMMITTED = new ConcurrentHashMap<>();
    private static final Set<UUID> REPORTING = ConcurrentHashMap.newKeySet();

    private SurvivalPendingEdit() {
    }

    public static void begin(UUID player) {
        PENDING.put(player, System.currentTimeMillis());
    }

    public static void end(UUID player) {
        PENDING.remove(player);
        COMMITTED.put(player, System.currentTimeMillis());
    }

    /**
     * Whether the player's edit is still being calculated, and WorldEdit should keep quiet about it.
     */
    public static boolean isPending(UUID player) {
        Long since = PENDING.get(player);
        if (since == null) {
            return false;
        }
        // A command that never reaches its commit leaves no trace to clean up after itself, so a
        // stale entry is treated as expired rather than muting the player forever.
        if (System.currentTimeMillis() - since > TIMEOUT_MS) {
            PENDING.remove(player);
            return false;
        }
        return true;
    }

    /**
     * Marks that SurvivalEdit is sending its own report, which must always reach the player.
     */
    public static void beginReport(UUID player) {
        REPORTING.add(player);
    }

    public static void endReport(UUID player) {
        REPORTING.remove(player);
    }

    /**
     * Whether a message from WorldEdit should be dropped because SurvivalEdit already spoke.
     */
    public static boolean shouldSuppress(UUID player, String text) {
        if (REPORTING.contains(player)) {
            return false;
        }
        if (isPending(player)) {
            return true;
        }
        Long committed = COMMITTED.get(player);
        if (committed == null) {
            return false;
        }
        if (System.currentTimeMillis() - committed > QUIET_AFTER_COMMIT_MS) {
            COMMITTED.remove(player);
            return false;
        }
        return text.startsWith(COMPLETION_LINE);
    }
}
