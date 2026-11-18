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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.extension.platform.SelectionGuard;
import com.sk89q.worldedit.neoforge.NeoForgeConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Stops an edit from changing blocks inside a chunk somebody else has claimed with FTB Chunks.
 *
 * <p>FTB Chunks guards a claim through its own events, but an edit writes straight into the chunk,
 * so nothing would stop a player from flattening a claim they cannot even break a block in. The
 * claim is therefore asked directly, before the edit is costed, with the same question FTB Chunks
 * asks a player who tries to break a block: {@code Protection.EDIT_BLOCK}.</p>
 *
 * <p>FTB Chunks is not a dependency of this mod. The API is reached through reflection and is only
 * looked up when the mod is actually installed, so a pack without it is unaffected. When the lookup
 * fails (a future FTB Chunks renaming its API), the failure is logged once and claim protection is
 * simply off rather than crashing edits.</p>
 */
public final class FtbChunksClaims {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String MOD_ID = "ftbchunks";
    private static final String API_CLASS = "dev.ftb.mods.ftbchunks.api.FTBChunksAPI";
    private static final String MANAGER_CLASS = "dev.ftb.mods.ftbchunks.api.ClaimedChunkManager";
    private static final String PROTECTION_CLASS = "dev.ftb.mods.ftbchunks.api.Protection";

    private static boolean looked;
    private static boolean available;
    private static boolean warned;
    private static Object api;
    private static Object editBlock;
    private static Method getManager;
    private static Method shouldPreventInteraction;

    private FtbChunksClaims() {
    }

    /** Whether claim protection is switched on and FTB Chunks is installed. */
    /**
     * Stops a claim from being made a selection corner in the first place, so a region can never be
     * drawn around land the player may not build in.
     *
     * <p>Both ways of selecting are covered, because the guard sits where every corner is set: the
     * selection axe (left and right click), {@code //pos1}, {@code //pos2}, {@code //pos} and the
     * {@code //hpos} variants. Refusing the corner with a message is friendlier than letting the
     * selection stand and refusing the edit later, and it keeps blocks the player cannot break out of
     * a region entirely.</p>
     */
    public static void installSelectionGuard() {
        SelectionGuard.set((actor, world, position) -> {
            ServerPlayer player = SurvivalMode.asServerPlayer(actor);
            if (player == null || !isBlocked(player, position)) {
                return true;
            }
            SurvivalMessages.error(player, "You cannot select " + position.x() + ", " + position.y()
                + ", " + position.z() + ": that block is inside a chunk claimed with FTB Chunks that"
                + " you may not build in.");
            return false;
        });
    }

    public static boolean enabled() {
        NeoForgeConfiguration config = SurvivalMode.config();
        if (config == null || !config.survivalFtbChunksClaims) {
            return false;
        }
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(MOD_ID);
    }

    /**
     * The first position this player may not edit because of a claim, or {@code null} when the whole
     * edit is allowed.
     *
     * <p>Only one position per chunk is asked about, so the cost of the check follows the number of
     * chunks an edit touches rather than the number of blocks in it.</p>
     */
    public static @Nullable BlockVector3 firstBlocked(ServerPlayer player, ServerLevel level,
                                                      Set<BlockVector3> positions) {
        if (positions.isEmpty() || !enabled() || level != player.level() || !load()) {
            return null;
        }

        Set<Long> checked = new HashSet<>();
        for (BlockVector3 position : positions) {
            if (!checked.add(ChunkPos.asLong(position.x() >> 4, position.z() >> 4))) {
                continue;
            }
            if (isBlocked(player, position)) {
                return position;
            }
        }
        return null;
    }

    /** Whether this one position is inside a claim the player may not build in. */
    public static boolean isBlocked(ServerPlayer player, BlockVector3 position) {
        if (!enabled() || !SurvivalMode.appliesTo(player) || player.hasPermissions(2) || !load()) {
            return false;
        }
        try {
            Object manager = getManager.invoke(api);
            if (manager == null) {
                // FTB Chunks has not finished starting up; nothing is claimed yet either.
                return false;
            }
            Object prevented = shouldPreventInteraction.invoke(manager, player, InteractionHand.MAIN_HAND,
                new BlockPos(position.x(), position.y(), position.z()), editBlock, null);
            return Boolean.TRUE.equals(prevented);
        } catch (Throwable ignored) {
            // A claim that cannot be read must not break the command it is guarding.
            return false;
        }
    }

    private static synchronized boolean load() {
        if (looked) {
            return available;
        }
        try {
            Class<?> apiHolder = Class.forName(API_CLASS);
            api = apiHolder.getMethod("api").invoke(null);
            if (api == null) {
                // Not initialised yet; try again on the next edit.
                return false;
            }
            Class<?> protectionClass = Class.forName(PROTECTION_CLASS);
            editBlock = protectionClass.getField("EDIT_BLOCK").get(null);
            getManager = api.getClass().getMethod("getManager");
            shouldPreventInteraction = Class.forName(MANAGER_CLASS).getMethod("shouldPreventInteraction",
                net.minecraft.world.entity.Entity.class, InteractionHand.class, BlockPos.class,
                protectionClass, net.minecraft.world.entity.Entity.class);
            available = true;
            looked = true;
        } catch (Throwable t) {
            // The API is not reachable yet (or has changed shape). Editing carries on without claim
            // protection rather than failing, and the lookup is retried on the next edit.
            available = false;
            if (!warned) {
                warned = true;
                LOGGER.warn("SurvivalEdit: FTB Chunks is installed but its claim API could not be read ({}),"
                    + " so claimed chunks will not stop edits. Set survival.ftbchunks-claims=false to"
                    + " silence this.", t.toString());
            }
        }
        return available;
    }
}
