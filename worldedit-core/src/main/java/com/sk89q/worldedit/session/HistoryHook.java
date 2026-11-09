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

package com.sk89q.worldedit.session;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extension.platform.Actor;

/**
 * Lets a platform take over the history commands.
 *
 * <p>WorldEdit keeps history in memory, per session, and forgets it when the player leaves. A
 * platform that stores history elsewhere installs a hook on {@link com.sk89q.worldedit.WorldEdit}
 * and every {@code //undo}, {@code //redo} and {@code //clearhistory} is offered to it first. When
 * the hook does not handle the call, the ordinary in-memory history runs, so a platform without a
 * hook behaves exactly as before.</p>
 */
public interface HistoryHook {

    /**
     * Undo the player's most recent applied operations.
     *
     * @param actor the actor running the command
     * @param session the session the command was run for
     * @param times how many operations to undo
     * @return {@code true} when the hook handled the command
     */
    boolean undo(Actor actor, LocalSession session, int times) throws WorldEditException;

    /**
     * Undo the applied operation closest to the actor.
     *
     * @param actor the actor running the command
     * @param session the session the command was run for
     * @return {@code true} when the hook handled the command
     */
    boolean undoNearest(Actor actor, LocalSession session) throws WorldEditException;

    /**
     * Redo the player's most recently undone operations.
     *
     * @param actor the actor running the command
     * @param session the session the command was run for
     * @param times how many operations to redo
     * @return {@code true} when the hook handled the command
     */
    boolean redo(Actor actor, LocalSession session, int times) throws WorldEditException;

    /**
     * Forget the player's history.
     *
     * @param actor the actor running the command
     * @param session the session the command was run for
     * @return {@code true} when the hook handled the command
     */
    boolean clear(Actor actor, LocalSession session) throws WorldEditException;

}
