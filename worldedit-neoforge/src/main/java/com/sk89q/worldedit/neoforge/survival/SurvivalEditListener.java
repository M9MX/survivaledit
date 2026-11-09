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
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.minecraft.server.level.ServerPlayer;

/**
 * Installs {@link SurvivalEditExtent} on the edit sessions of players who are editing in survival.
 *
 * <p>Every player edit is buffered, including one the player cannot pay for: the payment engine
 * decides whether the edit runs, so an unbuffered edit would be an edit nobody paid for. Undo and
 * redo do not pass through here; they are replayed from the player's stored history by
 * {@link SurvivalHistory}.</p>
 *
 * <p>Edits that are not charged at all, such as creative-mode edits, are buffered as well. They cost
 * nothing, but they still go through the queue so a large edit is spread over several ticks.</p>
 */
public final class SurvivalEditListener {

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }

        Actor actor = event.getActor();
        ServerPlayer serverPlayer = SurvivalMode.asServerPlayer(actor);
        if (serverPlayer == null) {
            return;
        }

        // Survival players pay; everyone else (creative, operators) edits for free.
        SurvivalEditJob.Kind kind = SurvivalMode.appliesTo(serverPlayer)
            ? SurvivalEditJob.Kind.PAID
            : SurvivalEditJob.Kind.FREE;

        event.setExtent(new SurvivalEditExtent(event.getExtent(), event.getWorld(), serverPlayer, kind));
    }
}
