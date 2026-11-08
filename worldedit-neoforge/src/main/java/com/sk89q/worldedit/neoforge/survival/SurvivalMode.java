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

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.neoforge.NeoForgeConfiguration;
import com.sk89q.worldedit.neoforge.NeoForgePlayer;
import com.sk89q.worldedit.neoforge.NeoForgeWorld;
import com.sk89q.worldedit.neoforge.NeoForgeWorldEdit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;

import java.util.Set;
import javax.annotation.Nullable;

/**
 * Decides whether WorldEdit is allowed to act on behalf of a player in survival mode.
 *
 * <p>WorldEdit normally refuses to do anything for a player who is not an operator or in creative
 * mode. SurvivalEdit lifts that restriction, but only when the storage mods required to pay for an
 * edit are installed. Affordability is not checked here: every command is allowed so that the
 * payment engine can explain what is missing, and the edit only runs once it has been paid for.</p>
 */
public final class SurvivalMode {

    private static final String AE2_MOD_ID = "ae2";
    private static final String WIRELESS_TERMINALS_MOD_ID = "ae2wtlib";

    /**
     * Commands that read or write schematics. These are refused in survival: an edit always has to
     * come out of the player's own storage, and a schematic is a way around that.
     */
    private static final Set<String> SCHEMATIC_PERMISSIONS = Set.of(
        "worldedit.schematic",
        "worldedit.schematic.load",
        "worldedit.schematic.save",
        "worldedit.schematic.share",
        "worldedit.schematic.delete",
        "worldedit.schematic.formats",
        "worldedit.schematic.list",
        "worldedit.clipboard.load",
        "worldedit.clipboard.save",
        "worldedit.clipboard.share"
    );

    /**
     * Commands that are creative-only. The wand item is crafted in survival instead.
     */
    private static final Set<String> CREATIVE_PERMISSIONS = Set.of(
        "worldedit.wand",
        "worldedit.wand.toggle",
        // Binding another item as the wand would get around having to craft the axe.
        "worldedit.setwand"
    );

    private SurvivalMode() {
    }

    static NeoForgeConfiguration config() {
        return NeoForgeWorldEdit.inst.getConfig();
    }

    /**
     * Whether both required mods are present. AE2WTLib is what provides the wireless terminals, so
     * without it there is nothing to charge an edit against.
     */
    public static boolean requiredModsLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(AE2_MOD_ID) && modList.isLoaded(WIRELESS_TERMINALS_MOD_ID);
    }

    /**
     * Whether survival editing is active at all on this server.
     *
     * <p>An edit is buffered whenever the server has survival editing enabled and the player is in
     * survival mode. Whether the edit actually runs is decided later by the payment engine, which
     * explains what is missing and asks for items; it never silently applies a free edit, so the
     * buffering gate must ignore whether the storage mods are present.</p>
     */
    public static boolean isActive() {
        NeoForgeConfiguration config = config();
        return config != null && config.survivalEnable;
    }

    public static boolean isSurvivalPlayer(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    /**
     * Whether survival editing applies to this player, i.e. they are in survival and the server has
     * survival editing turned on.
     */
    public static boolean appliesTo(ServerPlayer player) {
        return isActive() && isSurvivalPlayer(player);
    }

    /**
     * Whether the player carries a wireless terminal linked to an ME network.
     */
    public static boolean hasTerminal(ServerPlayer player) {
        if (!requiredModsLoaded()) {
            return false;
        }
        try {
            return Ae2Integration.hasGrid(player);
        } catch (Throwable ignored) {
            // AE2 failing to answer must never crash a permission check.
            return false;
        }
    }

    /**
     * Whether a permission is denied to a survival player no matter what they carry. Schematics are
     * always denied, and so is the creative-only wand.
     */
    public static boolean isDeniedInSurvival(String permission) {
        if (CREATIVE_PERMISSIONS.contains(permission)) {
            return true;
        }
        if (!config().survivalAllowSchematics && SCHEMATIC_PERMISSIONS.contains(permission)) {
            return true;
        }
        return permission.startsWith("worldedit.schematic.");
    }

    /**
     * Whether survival editing requires a wireless terminal even when the inventory could pay.
     */
    public static boolean requireTerminal() {
        return config().survivalRequireTerminal;
    }

    public static int blocksPerTick() {
        return Math.max(1, config().survivalBlocksPerTick);
    }

    public static int maxQueuedBlocks() {
        return config().survivalMaxQueuedBlocks;
    }

    /**
     * Resolves the Minecraft player behind a WorldEdit actor, or {@code null} for non-player actors.
     */
    public static @Nullable ServerPlayer asServerPlayer(Actor actor) {
        if (actor instanceof NeoForgePlayer player) {
            return player.getServerPlayer();
        }
        // Commands usually arrive wrapped in a PlayerProxy, which is the base player plus the
        // permission and CUI actors it was dispatched with. The UUID is shared, so the server
        // player can be looked up from the world the actor is editing in.
        if (actor instanceof Player player && player.getWorld() instanceof NeoForgeWorld world) {
            return world.getWorld().getServer().getPlayerList().getPlayer(player.getUniqueId());
        }
        return null;
    }

    void dump() {
        NeoForgeConfiguration config = config();
        if (config == null) {
            return;
        }
        System.out.println("SurvivalMode::" + config.survivalEnable + "/" + config.survivalRequireTerminal);
    }
}
