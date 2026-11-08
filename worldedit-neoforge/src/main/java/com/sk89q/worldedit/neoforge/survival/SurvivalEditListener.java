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

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.neoforge.NeoForgeConfiguration;
import com.sk89q.worldedit.session.UndoContext;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Installs {@link SurvivalEditExtent} on the edit sessions of players who are editing in survival.
 *
 * <p>Every player edit is buffered, including one the player cannot pay for: the payment engine
 * decides whether the edit runs, so an unbuffered edit would be an edit nobody paid for. An undo is
 * buffered too, but it is free and returns the blocks it removes; a redo is charged like a new
 * edit.</p>
 *
 * <p>Edits that are not charged at all, such as creative-mode edits, are buffered as well. They cost
 * nothing, but they still go through the queue so a large edit is spread over several ticks.</p>
 */
public final class SurvivalEditListener {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final Set<String> REPORTED_REASONS = ConcurrentHashMap.newKeySet();

    /**
     * How many edit sessions have been reported, so a broken hook shows up in the log without
     * flooding it.
     */
    private static final AtomicInteger SEEN = new AtomicInteger();

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        Actor actor = event.getActor();
        if (event.getStage() == EditSession.Stage.BEFORE_CHANGE && SEEN.getAndIncrement() < 20) {
            LOGGER.info("SurvivalEdit: edit session seen, actor={}",
                actor == null ? "none" : actor.getClass().getName() + " (" + actor.getName() + ")");
        }

        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }

        ServerPlayer serverPlayer = SurvivalMode.asServerPlayer(actor);
        if (serverPlayer == null) {
            return;
        }

        SurvivalEditJob.Kind kind;
        if (SurvivalMode.appliesTo(serverPlayer)) {
            kind = UndoContext.getMode() == UndoContext.Mode.UNDO
                ? SurvivalEditJob.Kind.UNDO
                : SurvivalEditJob.Kind.PAID;
        } else {
            String r = reason(serverPlayer);
            if (r != null && REPORTED_REASONS.add(r)) {
                LOGGER.info("SurvivalEdit: not charging {}: {}", serverPlayer.getName().getString(), r);
            }
            // Edits that are not paid for still run through the queue, so every edit is applied a
            // chunk at a time instead of a whole selection in one tick.
            kind = SurvivalEditJob.Kind.FREE;
        }

        event.setExtent(new SurvivalEditExtent(event.getExtent(), event.getWorld(), serverPlayer, kind));
        LOGGER.info("SurvivalEdit: buffering this edit for {} as {}",
            serverPlayer.getName().getString(), kind);
    }

    /**
     * Returns the reason survival editing is not active, or null when active.
     */
    private static @Nullable String reason(ServerPlayer player) {
        NeoForgeConfiguration config = SurvivalMode.config();
        if (config == null) {
            return "the configuration is not loaded";
        }
        if (!config.survivalEnable) {
            return "survival.enable is false";
        }
        if (!SurvivalMode.isSurvivalPlayer(player)) {
            return "player " + player.getName().getString() + " is not in Survival mode";
        }
        return null;
    }
}
