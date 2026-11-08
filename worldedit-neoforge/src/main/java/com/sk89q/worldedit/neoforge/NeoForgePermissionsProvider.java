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

package com.sk89q.worldedit.neoforge;

import com.sk89q.worldedit.neoforge.survival.SurvivalMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public interface NeoForgePermissionsProvider {

    boolean hasPermission(ServerPlayer player, String permission);

    void registerPermission(String permission);

    class VanillaPermissionsProvider implements NeoForgePermissionsProvider {

        private final NeoForgePlatform platform;

        public VanillaPermissionsProvider(NeoForgePlatform platform) {
            this.platform = platform;
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            NeoForgeConfiguration configuration = platform.getConfiguration();

            // Schematic access is always refused to survival players: an edit has to be paid for out
            // of the player's own storage, and a schematic is a way around that.
            if (SurvivalMode.isActive()
                && SurvivalMode.isSurvivalPlayer(player)
                && SurvivalMode.isDeniedInSurvival(permission)) {
                return false;
            }

            // Survival editing: allowed with no operator status or creative mode required. What the
            // edit costs is decided by the payment engine, which refuses the edit outright when the
            // player cannot pay for it.
            if (SurvivalMode.appliesTo(player)
                && (!SurvivalMode.requireTerminal() || SurvivalMode.hasTerminal(player))) {
                return true;
            }

            return configuration.cheatMode
                || ServerLifecycleHooks.getCurrentServer().getPlayerList().isOp(player.getGameProfile())
                || (configuration.creativeEnable && player.gameMode.getGameModeForPlayer() == GameType.CREATIVE);
        }

        @Override
        public void registerPermission(String permission) {
        }
    }
}
