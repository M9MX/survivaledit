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

/**
 * Marks the current thread as replaying history, so that platform code can tell an undo apart from
 * a normal edit or a redo.
 *
 * <p>An undo restores blocks that were already paid for when the original edit ran, so it must not
 * be charged again, and any blocks it removes have to be returned to whatever the edit was paid
 * from. A redo puts those blocks back, so it is charged like any other edit. Everything a platform
 * needs to know about that is this mode.</p>
 */
public final class UndoContext {

    /**
     * What the current thread is doing.
     */
    public enum Mode {
        /**
         * A normal edit.
         */
        NONE,
        /**
         * Replaying history backwards: free, and the removed blocks are returned to the payer.
         */
        UNDO,
        /**
         * Replaying history forwards: paid for like a new edit.
         */
        REDO
    }

    private static final ThreadLocal<Mode> MODE = ThreadLocal.withInitial(() -> Mode.NONE);

    private UndoContext() {
    }

    public static Mode getMode() {
        return MODE.get();
    }

    public static boolean isActive() {
        return MODE.get() != Mode.NONE;
    }

    public static boolean isUndo() {
        return MODE.get() == Mode.UNDO;
    }

    public static void begin(Mode mode) {
        MODE.set(mode);
    }

    public static void end() {
        MODE.set(Mode.NONE);
    }

    /**
     * Runs the given action with the given mode set, restoring the previous value afterwards.
     */
    public static void run(Mode mode, Runnable action) {
        Mode previous = MODE.get();
        MODE.set(mode);
        try {
            action.run();
        } finally {
            MODE.set(previous);
        }
    }
}
