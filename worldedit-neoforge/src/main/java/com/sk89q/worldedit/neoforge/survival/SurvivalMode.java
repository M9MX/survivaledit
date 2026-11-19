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

import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.neoforge.NeoForgeAdapter;
import com.sk89q.worldedit.neoforge.NeoForgeConfiguration;
import com.sk89q.worldedit.neoforge.NeoForgePlayer;
import com.sk89q.worldedit.neoforge.NeoForgeWorld;
import com.sk89q.worldedit.neoforge.NeoForgeWorldEdit;
import com.sk89q.worldedit.world.block.BlockType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
     * Commands that read or write schematics. These are refused in survival unless they are enabled
     * in the configuration: an edit always has to come out of the player's own storage, and a
     * schematic is a way around that.
     */
    private static final Set<String> SCHEMATIC_PERMISSIONS = Set.of(
        "worldedit.schematic",
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

    static @Nullable NeoForgeConfiguration config() {
        return NeoForgeWorldEdit.inst.getConfig();
    }

    /** Whether at least one provider-backed survival system is available. */
    public static boolean requiredModsLoaded() {
        return true;
    }

    public static boolean ae2Loaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(AE2_MOD_ID);
    }

    public static boolean hasPaymentProvider(ServerPlayer player) {
        return PaymentProviders.atLeastOneAvailable(player);
    }

    public static boolean isSurvivalPlayer(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
    }

    /**
     * Whether survival editing applies to this player, i.e. they are in survival.
     */
    public static boolean appliesTo(ServerPlayer player) {
        return isSurvivalPlayer(player);
    }

    /**
     * Whether the player carries a wireless terminal linked to an ME network.
     */
    public static boolean hasTerminal(ServerPlayer player) {
        if (!ae2Loaded()) {
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
     * denied unless they are enabled, and so is the creative-only wand.
     */
    public static boolean isDeniedInSurvival(String permission) {
        if (CREATIVE_PERMISSIONS.contains(permission)) {
            return true;
        }
        if (allowSchematics()) {
            return false;
        }
        return SCHEMATIC_PERMISSIONS.contains(permission) || permission.startsWith("worldedit.schematic.");
    }

    /**
     * Whether survival edits require a wireless terminal even when the inventory could pay.
     * SurvivalEdit always requires a wireless terminal; there is no setting for this.
     */
    public static boolean requireTerminal() {
        return false;
    }

    // ------------------------------------------------------------------
    // Protected blocks
    // ------------------------------------------------------------------

    /** Block tags from the configuration, resolved once and kept for the life of the server. */
    private static final Map<String, TagKey<Block>> BLOCK_TAGS = new HashMap<>();
    private static final TagKey<Block> NO_TAG = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("survivaledit", "none"));

    /**
     * Whether a survival edit may not replace this block.
     *
     * <p>This is the survival half of WorldEdit's disallowed blocks: it guards the block that is
     * already in the world, so an edit can never destroy it, and it is checked before the edit is
     * paid for. Creative-mode edits are not affected, so an operator can still build over one of
     * these deliberately.</p>
     */
    public static boolean isProtected(BlockType type) {
        NeoForgeConfiguration config = config();
        if (config == null || type == null) {
            return false;
        }

        String id = type.id();
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? id : id.substring(0, colon);

        // An exception (!ae2:test) wins over every rule that would protect the block, wherever it
        // appears in the list. Without that, a whole mod could not be protected with one or two
        // blocks left out.
        if (config.survivalAllowedBlocks.contains(id)
            || config.survivalAllowedMods.contains(namespace)
            || matchesTag(type, config.survivalAllowedTags)) {
            return false;
        }

        return config.survivalProtectedBlocks.contains(id)
            || config.survivalProtectedMods.contains(namespace)
            || matchesTag(type, config.survivalProtectedTags);
    }

    /**
     * Whether a position contains a block entity whose inventory or item-like NBT has contents.
     *
     * <p>SurvivalEdit never copies block-entity contents. Refusing the edit before payment avoids
     * silently destroying the contents of containers and similarly capable modded blocks.</p>
     */
    public static boolean blockEntityHasContents(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        if (blockEntity instanceof Container container && !container.isEmpty()) {
            return true;
        }
        try {
            CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
            return compoundHasItemContents(tag);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean compoundHasItemContents(CompoundTag compound) {
        for (String key : compound.getAllKeys()) {
            Tag value = compound.get(key);
            if (value instanceof ListTag list
                && (key.toLowerCase(Locale.ROOT).contains("item")
                    || key.toLowerCase(Locale.ROOT).contains("inventory"))) {
                for (int i = 0; i < list.size(); i++) {
                    Tag item = list.get(i);
                    if (item instanceof CompoundTag itemTag && !itemTag.isEmpty()) {
                        return true;
                    }
                }
            } else if (value instanceof CompoundTag child && compoundHasItemContents(child)) {
                return true;
            } else if (value instanceof ListTag nestedList && listHasItemContents(nestedList)) {
                return true;
            }
        }
        return false;
    }

    private static boolean listHasItemContents(ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            Tag value = list.get(i);
            if (value instanceof CompoundTag compound && compoundHasItemContents(compound)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTag(BlockType type, Set<String> tags) {
        if (tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (NeoForgeAdapter.adapt(type).defaultBlockState().is(tagKey(tag))) {
                return true;
            }
        }
        return false;
    }

    private static TagKey<Block> tagKey(String id) {
        return BLOCK_TAGS.computeIfAbsent(id, key -> {
            try {
                return TagKey.create(Registries.BLOCK, ResourceLocation.parse(key));
            } catch (RuntimeException e) {
                // A typo in the configuration must not break edits, it just matches nothing.
                return NO_TAG;
            }
        });
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** How many blocks all queued edits together may write per server tick. */
    public static int blocksPerTick() {
        NeoForgeConfiguration config = config();
        return config == null ? 1024 : Math.max(1, config.survivalBlocksPerTick);
    }

    /** How long the applier waits before it starts writing into a new chunk. */
    public static int perChunkDelayMs() {
        NeoForgeConfiguration config = config();
        return config == null ? 0 : Math.max(0, config.survivalPerChunkDelayMs);
    }

    /** The largest edit that may be made at once, or {@code 0} for no limit. */
    public static int maxBlocksPerEdit() {
        NeoForgeConfiguration config = config();
        return config == null ? 250_000 : config.survivalMaxBlocksPerEdit;
    }

    /** How many edits are kept per player, or {@code 0} for no limit. */
    public static int historyLimit() {
        NeoForgeConfiguration config = config();
        return config == null ? 250 : Math.max(0, config.survivalHistoryLimit);
    }

    /**
     * The smallest edit that is reported in chat, or {@code -1} when no edit is reported.
     *
     * <p>{@code 0} reports every edit. Refusals and refunds ignore this threshold: a player must
     * always see why an edit stopped and what came back to them.</p>
     */
    public static int messageThreshold() {
        NeoForgeConfiguration config = config();
        return config == null ? 0 : Math.max(-1, config.survivalMessageThreshold);
    }

    /**
     * Whether an edit of this many blocks should say anything at all.
     *
     * <p>Below the threshold an edit is silent, including what it returned; only errors are shown,
     * because those are what the player has to act on.</p>
     */
    public static boolean reports(int blocks) {
        int threshold = messageThreshold();
        return threshold >= 0 && blocks >= threshold;
    }

    /** Whether {@code //schem} and the clipboard save/load commands are allowed in survival. */
    public static boolean allowSchematics() {
        NeoForgeConfiguration config = config();
        return config != null && config.survivalAllowSchematics;
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
}
