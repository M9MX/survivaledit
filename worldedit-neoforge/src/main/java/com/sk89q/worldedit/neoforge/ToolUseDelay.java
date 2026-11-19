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

/**
 * The hold-to-use delay for SurvivalEdit tools.
 *
 * <p>Minecraft repeats a held right click every 4 ticks. SurvivalEdit tools use the configured
 * value instead, so a brush can repeat faster or slower. The repeat is timed by the client, so the
 * server sends its setting to each player when they join.</p>
 *
 * <p>The client value is the one the server last sent, because a player on a server has to obey
 * that server's setting rather than their own copy of the file.</p>
 */
public final class ToolUseDelay {

    /** Vanilla's repeat delay is 4 ticks, which is also the default here. */
    public static final int DEFAULT_TICKS = 4;

    private static volatile int clientTicks = DEFAULT_TICKS;

    private ToolUseDelay() {
    }

    /** The delay the client repeats a held right click at. */
    public static int clientTicks() {
        return clientTicks;
    }

    /** Called on the client when the server sends its setting. */
    public static void setClientTicks(int ticks) {
        clientTicks = Math.max(0, ticks);
    }

    /** The configured value, from the server's worldedit.properties. */
    public static int configuredTicks() {
        NeoForgeConfiguration config = NeoForgeWorldEdit.inst == null ? null : NeoForgeWorldEdit.inst.getConfig();
        return config == null ? DEFAULT_TICKS : Math.max(0, config.survivalToolUseDelay);
    }
}
