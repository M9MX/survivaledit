/* WorldEdit, a Minecraft world manipulation toolkit
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

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

public class NeoForgeConfiguration extends PropertiesConfiguration {

    // SurvivalEdit is always active in survival: there is no survival.enable toggle.
    // In survival the only way to pay for an edit is through the ME network and a
    // wireless terminal, and that requirement cannot be disabled.
    public static final boolean SURVIVAL_REQUIRE_TERMINAL = true;

    /** How many blocks all queued edits together may write per server tick. */
    public int survivalBlocksPerTick = 1024;
    /** How long the applier waits before it starts writing into a new chunk. */
    public int survivalPerChunkDelayMs = 0;
    /** The largest edit that may be made at once, or 0 for no limit. */
    public int survivalMaxBlocksPerEdit = 250_000;
    /** How many edits are kept per player, or 0 for no limit. */
    public int survivalHistoryLimit = 250;
    /** Whether //schem and the clipboard save/load commands are allowed in survival. */
    public boolean survivalAllowSchematics = false;
    /** Whether items that do not fit anywhere are dropped on the ground instead of discarded. */
    public boolean survivalDropIfNetworkIsFull = true;

    /** Blocks a survival edit may not overwrite, by exact block id. */
    public final Set<String> survivalProtectedBlocks = new HashSet<>();
    /** Whole mods whose blocks a survival edit may not overwrite, by namespace. */
    public final Set<String> survivalProtectedMods = new HashSet<>();
    /** Block tags whose blocks a survival edit may not overwrite, as {@code namespace:path}. */
    public final Set<String> survivalProtectedTags = new HashSet<>();

    /** Blocks that stay editable even though a broader rule would protect them. */
    public final Set<String> survivalAllowedBlocks = new HashSet<>();
    /** Mods whose blocks stay editable even though a broader rule would protect them. */
    public final Set<String> survivalAllowedMods = new HashSet<>();
    /** Block tags that stay editable even though a broader rule would protect them. */
    public final Set<String> survivalAllowedTags = new HashSet<>();

    /**
     * The blocks a survival edit may not overwrite, unless the server owner removes them.
     *
     * <p>Three forms are accepted: {@code minecraft:bedrock} for a single block, {@code @ae2} for
     * every block of a mod, and {@code #minecraft:logs} for a block tag. Only the block that is
     * already in the world is protected, so a build may be pasted next to one of these, never over
     * it. Creative-mode edits are not restricted.</p>
     *
     * <p>The curated blocks are the ones that are lost rather than replaced: things that cannot be
     * mined back (bedrock, portals, spawners), things holding contents WorldEdit never copies
     * (containers), and the expensive multiblock cores of the tech mods in the pack.</p>
     */
    private static final String[] DEFAULT_PROTECTED_BLOCKS = {
        // Vanilla: cannot be obtained again, or is destroyed for good.
        "minecraft:bedrock",
        "minecraft:barrier",
        "minecraft:command_block",
        "minecraft:chain_command_block",
        "minecraft:repeating_command_block",
        "minecraft:structure_block",
        "minecraft:structure_void",
        "minecraft:jigsaw",
        "minecraft:spawner",
        "minecraft:trial_spawner",
        "minecraft:vault",
        "minecraft:end_portal",
        "minecraft:end_portal_frame",
        "minecraft:end_gateway",
        "minecraft:nether_portal",
        "minecraft:dragon_egg",
        "minecraft:reinforced_deepslate",
        "minecraft:budding_amethyst",
        "minecraft:heavy_core",
        // Vanilla: expensive or hard to place again.
        "minecraft:beacon",
        "minecraft:conduit",
        "minecraft:respawn_anchor",
        "minecraft:lodestone",
        "minecraft:enchanting_table",
        "minecraft:netherite_block",
        // Vanilla: containers. An edit never copies what is inside.
        "minecraft:chest",
        "minecraft:trapped_chest",
        "minecraft:barrel",
        "minecraft:ender_chest",
        "minecraft:shulker_box",
        // AE2: ME network cores.
        "ae2:controller",
        "ae2:drive",
        "ae2:chest",
        "ae2:energy_acceptor",
        "ae2:energy_cell",
        "ae2:dense_energy_cell",
        "ae2:spatial_io_port",
        "ae2:spatial_pylon",
        "ae2:quantum_ring",
        "ae2:quantum_link",
        "ae2:security_station",
        "ae2:wireless_access_point",
        "ae2:pattern_provider",
        "ae2:interface",
        "ae2:molecular_assembler",
        "ae2:inscriber",
        "ae2:crafting_unit",
        // Mekanism: the machines that cost a fortune to rebuild.
        "mekanism:digital_miner",
        "mekanism:quantum_entangloporter",
        "mekanism:teleporter",
        "mekanism:antiprotonic_nucleosynthesizer",
        "mekanism:fusion_reactor_controller",
        "mekanism:supercharged_coil",
        "mekanism:sps",
        // AllTheModium: the endgame metals of this pack.
        "allthemodium:allthemodium_block",
        "allthemodium:vibranium_block",
        "allthemodium:unobtainium_block",
    };

    /**
     * Keys from earlier builds that no longer do anything. They are removed from the file so a
     * server owner does not keep tuning settings that are ignored.
     */
    private static final String[] OBSOLETE_KEYS = {
        "survival.enable",
        "survival.max-queued-blocks",
        "survivaledit.max_blocks_per_job",
        "survivaledit.max_blocks_per_second",
        "survivaledit.per_block_delay_ms",
        "survivaledit.per_chunk_delay_ms",
        "survivaledit.server_batch_optimization",
    };

    public NeoForgeConfiguration(NeoForgeWorldEdit mod) {
        super(mod.getWorkingDir().resolve("worldedit.properties"));
    }

    @Override
    public void load() {
        super.load();

        // SurvivalEdit's axe replaces WorldEdit's wooden wand everywhere, so the wand item is fixed
        // rather than read from the configuration file.
        wandItem = SurvivalEditItems.AXE_ID;

        survivalBlocksPerTick = Math.max(1, getInt("survival.blocks-per-tick", survivalBlocksPerTick));
        survivalPerChunkDelayMs = Math.max(0, getInt("survival.per-chunk-delay-ms", survivalPerChunkDelayMs));
        survivalMaxBlocksPerEdit = Math.max(0, getInt("survival.max-blocks-per-edit", survivalMaxBlocksPerEdit));
        survivalHistoryLimit = Math.max(0, getInt("survival.history-limit", survivalHistoryLimit));
        survivalAllowSchematics = getBool("survival.allow-schematics", survivalAllowSchematics);
        survivalDropIfNetworkIsFull = getBool("survival.drop-if-network-is-full", survivalDropIfNetworkIsFull);
        readProtectedBlocks(getStringSet("survival.protected-blocks", DEFAULT_PROTECTED_BLOCKS));

        for (String key : OBSOLETE_KEYS) {
            properties.remove(key);
        }

        // Write once more now that the survival keys are known, so the file on disk always matches
        // what this build actually reads.
        store();
    }

    /**
     * Sorts the configured entries into block ids, mod namespaces and block tags.
     *
     * <p>Accepted: {@code minecraft:bedrock}, {@code bedrock} (assumed to be vanilla),
     * {@code @ae2} or {@code ae2:*} for a whole mod, and {@code #minecraft:logs} or
     * {@code #logs} for a block tag. A leading {@code !} makes the entry an exception:
     * {@code @ae2,!ae2:controller} protects all of AE2 but leaves its controller editable. An
     * exception always wins, wherever it appears in the list.</p>
     */
    private void readProtectedBlocks(Set<String> entries) {
        survivalProtectedBlocks.clear();
        survivalProtectedMods.clear();
        survivalProtectedTags.clear();
        survivalAllowedBlocks.clear();
        survivalAllowedMods.clear();
        survivalAllowedTags.clear();

        for (String entry : entries) {
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            boolean allow = value.startsWith("!");
            if (allow) {
                value = value.substring(1).trim();
                if (value.isEmpty()) {
                    continue;
                }
            }

            if (value.startsWith("@")) {
                (allow ? survivalAllowedMods : survivalProtectedMods).add(value.substring(1));
            } else if (value.endsWith(":*")) {
                (allow ? survivalAllowedMods : survivalProtectedMods)
                    .add(value.substring(0, value.length() - 2));
            } else if (value.startsWith("#")) {
                String tag = value.substring(1);
                (allow ? survivalAllowedTags : survivalProtectedTags)
                    .add(tag.contains(":") ? tag : "minecraft:" + tag);
            } else {
                (allow ? survivalAllowedBlocks : survivalProtectedBlocks)
                    .add(value.contains(":") ? value : "minecraft:" + value);
            }
        }
    }

    /**
     * Writes the configuration file from the defaults file shipped with the mod.
     *
     * <p>WorldEdit's loader writes every key it knows about. This fork ships a hand-written
     * defaults file instead, and writes the file from it, so the configuration contains only the
     * keys that do something here, keeps its comments, and never carries a leftover key from an
     * older build. Values the server owner has changed are written back as they were set.</p>
     */
    @Override
    protected void store() {
        String template = bundledDefaults();
        if (template == null) {
            super.store();
            return;
        }

        StringBuilder out = new StringBuilder(template.length() + 128);
        for (String line : template.split("\\R")) {
            String trimmed = line.trim();
            int split = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || split <= 0) {
                out.append(line).append('\n');
                continue;
            }
            String key = trimmed.substring(0, split);
            String value = properties.getProperty(key, trimmed.substring(split + 1));
            out.append(key).append('=').append(value).append('\n');
        }

        try (Writer writer = Files.newBufferedWriter(path.toPath(), StandardCharsets.UTF_8)) {
            writer.write(out.toString());
        } catch (IOException e) {
            super.store();
        }
    }

    private static @Nullable String bundledDefaults() {
        try (InputStream stream = NeoForgeConfiguration.class
            .getResourceAsStream("/defaults/worldedit.properties")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public Path getWorkingDirectoryPath() {
        return NeoForgeWorldEdit.inst.getWorkingDir();
    }
}
