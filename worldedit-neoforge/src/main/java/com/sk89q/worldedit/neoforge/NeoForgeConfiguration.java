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

import com.sk89q.worldedit.neoforge.item.SurvivalEditItems;
import com.sk89q.worldedit.util.PropertiesConfiguration;

import java.nio.file.Path;

public class NeoForgeConfiguration extends PropertiesConfiguration {

    public boolean creativeEnable = false;
    public boolean cheatMode = false;

    // SurvivalEdit
    public boolean survivalEnable = true;
    public boolean survivalRequireTerminal = false;
    public boolean survivalAllowSchematics = false;
    public boolean survivalDropIfNetworkIsFull = true;
    public int survivalBlocksPerTick = 1024;
    public int survivalMaxQueuedBlocks = 250_000;

    public NeoForgeConfiguration(NeoForgeWorldEdit mod) {
        super(mod.getWorkingDir().resolve("worldedit.properties"));
    }

    @Override
    public void load() {
        super.load();

        // SurvivalEdit's axe replaces WorldEdit's wooden wand everywhere, so the wand item is fixed
        // rather than read from the configuration file.
        wandItem = SurvivalEditItems.AXE_ID;
    }

    @Override
    protected void loadExtra() {
        creativeEnable = getBool("use-in-creative", false);
        cheatMode = getBool("cheat-mode", false);

        survivalEnable = getBool("survival.enable", survivalEnable);
        survivalRequireTerminal = getBool("survival.require-wireless-terminal", survivalRequireTerminal);
        survivalAllowSchematics = getBool("survival.allow-schematics", survivalAllowSchematics);
        survivalDropIfNetworkIsFull = getBool("survival.drop-if-network-is-full", survivalDropIfNetworkIsFull);
        survivalBlocksPerTick = Math.max(1, getInt("survival.blocks-per-tick", survivalBlocksPerTick));
        survivalMaxQueuedBlocks = getInt("survival.max-queued-blocks", survivalMaxQueuedBlocks);
    }

    @Override
    public Path getWorkingDirectoryPath() {
        return NeoForgeWorldEdit.inst.getWorkingDir();
    }
}
