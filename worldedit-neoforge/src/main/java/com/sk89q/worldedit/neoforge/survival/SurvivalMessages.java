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
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.event.ClickEvent;
import com.sk89q.worldedit.util.formatting.text.format.Style;
import com.sk89q.worldedit.util.formatting.text.format.TextDecoration;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Sends SurvivalEdit messages through WorldEdit's own actor, so they are coloured and formatted
 * exactly like every other WorldEdit message.
 */
final class SurvivalMessages {

    private SurvivalMessages() {
    }

    /**
     * Sends a normal message, in the same style as WorldEdit's own informational output.
     */
    static void info(ServerPlayer player, String message) {
        info(player, TextComponent.of(message));
    }

    static void info(ServerPlayer player, Component message) {
        UUID playerId = player.getUUID();
        // This is the one line the player must see about their edit, so it is allowed through even
        // while WorldEdit's own copy of it is being held back.
        SurvivalPendingEdit.beginReport(playerId);
        try {
            actorFor(player).printInfo(message);
        } finally {
            SurvivalPendingEdit.endReport(playerId);
        }
    }

    /**
     * Sends a refusal, in the same style as WorldEdit's own errors.
     */
    static void error(ServerPlayer player, String message) {
        error(player, TextComponent.of(message));
    }

    static void error(ServerPlayer player, Component message) {
        UUID playerId = player.getUUID();
        SurvivalPendingEdit.beginReport(playerId);
        try {
            actorFor(player).printError(message);
        } finally {
            SurvivalPendingEdit.endReport(playerId);
        }
    }

    /**
     * A command shown in chat, drawn bold and clickable so it is easy to run.
     */
    static Component action(String label, String command) {
        return TextComponent.of(label, Style.builder()
            .decoration(TextDecoration.BOLD, true)
            .clickEvent(ClickEvent.runCommand(command))
            .build());
    }

    private static Actor actorFor(ServerPlayer player) {
        return NeoForgeAdapter.adaptPlayer(player);
    }
}
