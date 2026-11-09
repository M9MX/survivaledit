/*
 * SurvivalEdit, a WorldEdit fork tuned for survival play
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

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.HistoryHook;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * The long-term edit history of every player.
 *
 * <p>WorldEdit's own history lives in memory, holds fifteen edits, and is gone when the player
 * disconnects or the server restarts. SurvivalEdit keeps its edits on disk instead, per player, so
 * {@code //undo} still works the next day and {@code //undo nearest} can find an edit from the
 * other side of the map. This class takes over the history commands through
 * {@link HistoryHook}.</p>
 *
 * <p>An edit is recorded once it has actually been applied, together with the block it replaced.
 * Undoing writes those blocks back, and returns items for exactly the blocks that came out of the
 * player's storage and are still standing. Blocks the player mined or replaced in the meantime are
 * not paid back; blocks that were never paid for are voided. That is what keeps undo from being a
 * way to duplicate items.</p>
 */
public final class SurvivalHistory implements HistoryHook {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static @Nullable SurvivalHistory instance;

    private final Path root;
    private final Map<UUID, PlayerHistory> players = new HashMap<>();

    private SurvivalHistory(Path root) {
        this.root = root;
    }

    /**
     * Installs the history hook, replacing the in-memory history for every player.
     *
     * <p>Everything is stored under the save's own folder name, so a player's undo history and the
     * ownership ledger of their blocks belong to one world. Opening a different save starts with an
     * empty history: an edit recorded in one world can never be undone into another, and a block
     * bought in one world can never be refunded in a world it does not exist in.</p>
     *
     * @param workingDir the game directory the mod stores its files in
     * @param world the save folder name, e.g. {@code world}
     */
    public static void install(Path workingDir, String world) {
        String folder = worldFolder(world);
        Path historyRoot = workingDir.resolve("survival-history");
        Path claimsRoot = workingDir.resolve("survival-claims");
        if (folder != null) {
            historyRoot = historyRoot.resolve(folder);
            claimsRoot = claimsRoot.resolve(folder);
        }
        LOGGER.info("SurvivalEdit: edit history and claims for world '{}' are stored in {}",
            folder == null ? "all" : folder, historyRoot.toAbsolutePath());

        SurvivalHistory history = new SurvivalHistory(historyRoot);
        instance = history;
        SurvivalClaims.install(claimsRoot);
        WorldEdit.getInstance().setHistoryHook(history);
    }

    /**
     * Turns a save folder name into something safe to use as a directory name.
     */
    private static @Nullable String worldFolder(String world) {
        if (world == null) {
            return null;
        }
        String safe = world.replaceAll("[^A-Za-z0-9._-]", "_").trim();
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? null : safe;
    }

    /**
     * Saves the block ownership ledger before the server stops.
     */
    public static void shutdown() {
        SurvivalClaims claims = SurvivalClaims.get();
        if (claims != null) {
            claims.saveAll();
        }
    }

    static @Nullable SurvivalHistory get() {
        return instance;
    }

    // ------------------------------------------------------------------
    // Recording edits
    // ------------------------------------------------------------------

    /**
     * Stores an applied edit.
     *
     * <p>Called from the job that applied it, on the server thread. An edit larger than the
     * per-edit block limit is not stored, so a single enormous free edit cannot fill the disk.</p>
     */
    void record(ServerPlayer player, String dimension, boolean paid, Map<Item, Long> charged,
                List<BlockVector3> positions, List<BaseBlock> previous, List<BaseBlock> next) {
        int count = positions.size();
        if (count == 0) {
            return;
        }
        int limit = SurvivalMode.maxBlocksPerEdit();
        if (limit > 0 && count > limit) {
            LOGGER.debug("SurvivalEdit: not storing a {}-block edit for {} (limit {})",
                count, player.getName().getString(), limit);
            return;
        }

        PlayerHistory history = history(player.getUUID());
        try {
            Files.createDirectories(history.dir);
            long id = history.nextId++;
            Path file = history.dir.resolve(id + ".edit");
            SurvivalEditRecord record = SurvivalEditRecord.write(file, id, System.currentTimeMillis(),
                dimension, paid, charged,
                positions.toArray(new BlockVector3[0]),
                previous.toArray(new BaseBlock[0]),
                next.toArray(new BaseBlock[0]));
            history.entries.add(record);
            prune(history);
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not store the edit history for {}", player.getName().getString(), e);
        }
    }

    /**
     * Drops the oldest stored edits until the player is inside the history limit.
     */
    private void prune(PlayerHistory history) {
        int limit = SurvivalMode.historyLimit();
        if (limit <= 0) {
            return;
        }
        boolean changed = false;
        while (history.entries.size() > limit) {
            SurvivalEditRecord victim = null;
            // Prefer the oldest edit that is currently applied: dropping an undone edit would
            // silently break that player's redo.
            for (SurvivalEditRecord entry : history.entries) {
                if (!history.undone.contains(entry.id)) {
                    victim = entry;
                    break;
                }
            }
            if (victim == null) {
                victim = history.entries.get(0);
            }
            history.entries.remove(victim);
            history.undone.remove(victim.id);
            history.redoStack.remove(victim.id);
            victim.delete();
            changed = true;
        }
        if (changed) {
            persist(history);
        }
    }

    // ------------------------------------------------------------------
    // History commands
    // ------------------------------------------------------------------

    @Override
    public boolean undo(Actor actor, LocalSession session, int times) throws WorldEditException {
        ServerPlayer player = playerFor(actor);
        if (player == null) {
            return true;
        }
        if (SurvivalEditQueue.isBusy(player)) {
            actor.printError(TextComponent.of("Your last edit is still being applied. Try again in a moment."));
            return true;
        }

        PlayerHistory history = history(player.getUUID());
        load(history);

        int count = 0;
        boolean failed = false;
        for (int i = 0; i < times; i++) {
            SurvivalEditRecord record = history.lastApplied();
            if (record == null) {
                break;
            }
            if (!undo(player, actor, history, record)) {
                failed = true;
                break;
            }
            count++;
        }

        if (count == 0 && !failed) {
            actor.printError(TranslatableComponent.of("worldedit.undo.none"));
        }
        return true;
    }

    @Override
    public boolean undoNearest(Actor actor, LocalSession session) throws WorldEditException {
        ServerPlayer player = playerFor(actor);
        if (player == null) {
            return true;
        }
        if (SurvivalEditQueue.isBusy(player)) {
            actor.printError(TextComponent.of("Your last edit is still being applied. Try again in a moment."));
            return true;
        }

        PlayerHistory history = history(player.getUUID());
        load(history);

        String dimension = player.level().dimension().location().toString();
        SurvivalEditRecord nearest = null;
        double best = Double.MAX_VALUE;
        for (SurvivalEditRecord record : history.entries) {
            if (history.undone.contains(record.id) || !record.isSameWorld(dimension)) {
                continue;
            }
            double distance = record.distanceTo(player.getX(), player.getY(), player.getZ());
            if (distance < best) {
                best = distance;
                nearest = record;
            }
        }

        if (nearest == null) {
            actor.printError(TextComponent.of("There is no applied edit to undo in this world."));
        } else {
            undo(player, actor, history, nearest);
        }
        return true;
    }

    @Override
    public boolean redo(Actor actor, LocalSession session, int times) throws WorldEditException {
        ServerPlayer player = playerFor(actor);
        if (player == null) {
            return true;
        }
        if (SurvivalEditQueue.isBusy(player)) {
            actor.printError(TextComponent.of("Your last edit is still being applied. Try again in a moment."));
            return true;
        }

        PlayerHistory history = history(player.getUUID());
        load(history);

        int count = 0;
        for (int i = 0; i < times; i++) {
            SurvivalEditRecord record = history.lastUndone();
            if (record == null || !redo(player, actor, history, record)) {
                break;
            }
            count++;
        }
        if (count == 0) {
            actor.printError(TranslatableComponent.of("worldedit.redo.none"));
        }
        return true;
    }

    @Override
    public boolean clear(Actor actor, LocalSession session) throws WorldEditException {
        ServerPlayer player = playerFor(actor);
        if (player == null) {
            return true;
        }
        PlayerHistory history = history(player.getUUID());
        load(history);
        int size = history.entries.size();
        for (SurvivalEditRecord record : history.entries) {
            record.delete();
        }
        history.entries.clear();
        history.undone.clear();
        history.redoStack.clear();
        persist(history);
        actor.printInfo(TextComponent.of("Cleared " + size + (size == 1 ? " edit" : " edits") + " from your history."));
        return true;
    }

    private boolean undo(ServerPlayer player, Actor actor, PlayerHistory history, SurvivalEditRecord record) {
        ServerLevel level = levelOf(player, record.dimension);
        if (level == null) {
            actor.printError(TextComponent.of("That edit is in " + record.dimension + ", which is not loaded."));
            return false;
        }
        SurvivalEditRecord.BlockDiff diff;
        try {
            diff = record.blocks();
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not read edit {} for {}", record.id, player.getName().getString(), e);
            actor.printError(TextComponent.of("That edit could not be read from disk, so it cannot be undone."));
            return false;
        }

        // A block that was bought, then replaced, was already paid back at that moment. Putting it
        // back therefore has to buy it again, otherwise covering a block and undoing the cover
        // would mint a free copy. Nothing is charged in the normal case, where no block was ever
        // paid back.
        Map<Item, Long> reclaim = returningCost(level, diff.positions, diff.previous);
        SurvivalPayment.Result payment = SurvivalPayment.charge(player, reclaim);
        if (!payment.paid()) {
            actor.printError(TextComponent.of("Cannot undo that edit: " + payment.reason()
                + ". Those blocks were already returned to you when they were replaced."));
            return false;
        }

        history.undone.add(record.id);
        history.redoStack.add(record.id);
        persist(history);

        SurvivalEditQueue.enqueueReplay(player, level, SurvivalEditJob.Kind.UNDO, record,
            diff.positions, diff.previous, reclaim);

        String when = TIME.format(Instant.ofEpochMilli(record.created).atZone(ZoneId.systemDefault()));
        actor.printInfo(TextComponent.of("Undoing the edit from " + when + " (" + record.blockCount
            + " blocks). Anything that came from your storage is returned as it is removed."));
        return true;
    }

    private boolean redo(ServerPlayer player, Actor actor, PlayerHistory history, SurvivalEditRecord record) {
        ServerLevel level = levelOf(player, record.dimension);
        if (level == null) {
            actor.printError(TextComponent.of("That edit is in " + record.dimension + ", which is not loaded."));
            return false;
        }
        SurvivalEditRecord.BlockDiff diff;
        try {
            diff = record.blocks();
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not read edit {} for {}", record.id, player.getName().getString(), e);
            actor.printError(TextComponent.of("That edit could not be read from disk, so it cannot be redone."));
            return false;
        }

        // Only the blocks that are actually missing are charged for, so a redo of an edit that was
        // only partly undone does not cost the full price again.
        Map<Item, Long> cost = redoCost(level, diff);
        SurvivalPayment.Result payment = SurvivalPayment.charge(player, cost);
        if (!payment.paid()) {
            actor.printError(TextComponent.of("Cannot redo that edit: " + payment.reason() + "."));
            return false;
        }

        history.undone.remove(record.id);
        history.redoStack.remove(record.id);
        persist(history);

        SurvivalEditQueue.enqueueReplay(player, level, SurvivalEditJob.Kind.REDO, record,
            diff.positions, diff.next, cost);

        String when = TIME.format(Instant.ofEpochMilli(record.created).atZone(ZoneId.systemDefault()));
        actor.printInfo(TextComponent.of("Redoing the edit from " + when + " (" + record.blockCount + " blocks)."));
        return true;
    }

    /**
     * Counts the blocks an undo would put back that were already refunded once.
     *
     * <p>Only positions where the block is actually missing are counted, because a position that
     * already holds the right block is stepped over by the applier and costs nothing.</p>
     */
    private static Map<Item, Long> returningCost(ServerLevel level, BlockVector3[] positions,
                                                 BaseBlock[] targets) {
        SurvivalClaims claims = SurvivalClaims.get();
        if (claims == null) {
            return Map.of();
        }
        String dimension = level.dimension().location().toString();
        Map<Item, Long> cost = new LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
            Item item = ItemLedger.itemFor(targets[i]);
            if (item == null) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState target = SurvivalEditJob.nativeState(targets[i]);
            if (target == null) {
                continue;
            }
            BlockVector3 position = positions[i];
            BlockPos pos = new BlockPos(position.getX(), position.getY(), position.getZ());
            if (level.getBlockState(pos).equals(target)) {
                continue;
            }
            if (claims.isSpent(dimension, pos.asLong(), Block.getId(target))) {
                cost.merge(item, 1L, Long::sum);
            }
        }
        return cost;
    }

    private static Map<Item, Long> redoCost(ServerLevel level, SurvivalEditRecord.BlockDiff diff) {
        Map<Item, Long> cost = new LinkedHashMap<>();
        for (int i = 0; i < diff.size(); i++) {
            BaseBlock next = diff.next[i];
            Item item = ItemLedger.itemFor(next);
            if (item == null) {
                continue;
            }
            BlockVector3 position = diff.positions[i];
            BlockPos pos = new BlockPos(position.getX(), position.getY(), position.getZ());
            net.minecraft.world.level.block.state.BlockState target = SurvivalEditJob.nativeState(next);
            if (target == null || level.getBlockState(pos).equals(target)) {
                continue;
            }
            cost.merge(item, 1L, Long::sum);
        }
        return cost;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private PlayerHistory history(UUID playerId) {
        PlayerHistory history = players.get(playerId);
        if (history == null) {
            history = new PlayerHistory(playerId, root.resolve(playerId.toString()));
            players.put(playerId, history);
        }
        load(history);
        return history;
    }

    private void load(PlayerHistory history) {
        if (history.loaded) {
            return;
        }
        history.loaded = true;
        if (!Files.isDirectory(history.dir)) {
            return;
        }

        List<SurvivalEditRecord> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(history.dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".edit")).forEach(path -> {
                try {
                    entries.add(SurvivalEditRecord.read(path));
                } catch (Exception e) {
                    LOGGER.warn("SurvivalEdit: skipping unreadable edit record {}", path, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not read the edit history in {}", history.dir, e);
            return;
        }
        entries.sort((a, b) -> Long.compare(a.id, b.id));
        history.entries.addAll(entries);
        for (SurvivalEditRecord entry : entries) {
            history.nextId = Math.max(history.nextId, entry.id + 1);
        }

        Path state = history.dir.resolve("state.log");
        if (Files.isRegularFile(state)) {
            try {
                for (String line : Files.readAllLines(state)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        long id = Long.parseLong(trimmed);
                        if (history.find(id) != null && history.redoStack.add(id)) {
                            history.undone.add(id);
                        }
                    } catch (NumberFormatException ignored) {
                        // A damaged line only loses one redo entry.
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("SurvivalEdit: could not read the undo state in {}", state, e);
            }
        }
    }

    /**
     * Saves the redo stack, which is the only history state that is not implied by the files.
     */
    private void persist(PlayerHistory history) {
        if (!Files.isDirectory(history.dir)) {
            return;
        }
        List<String> lines = new ArrayList<>(history.redoStack.size());
        for (Long id : history.redoStack) {
            lines.add(Long.toString(id));
        }
        try {
            Files.write(history.dir.resolve("state.log"), lines);
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not save the undo state in {}", history.dir, e);
        }
    }

    private static @Nullable ServerLevel levelOf(ServerPlayer player, String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null || player.getServer() == null) {
            return null;
        }
        return player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static @Nullable ServerPlayer playerFor(Actor actor) {
        return SurvivalMode.asServerPlayer(actor);
    }

    /**
     * The stored history of one player.
     */
    private static final class PlayerHistory {

        final UUID playerId;
        final Path dir;
        final List<SurvivalEditRecord> entries = new ArrayList<>();
        /** Ids that are undone, in the order they were undone. The last one is redone first. */
        final List<Long> redoStack = new ArrayList<>();
        final Set<Long> undone = new HashSet<>();
        long nextId = 1;
        boolean loaded;

        PlayerHistory(UUID playerId, Path dir) {
            this.playerId = playerId;
            this.dir = dir;
        }

        @Nullable
        SurvivalEditRecord find(long id) {
            for (SurvivalEditRecord record : entries) {
                if (record.id == id) {
                    return record;
                }
            }
            return null;
        }

        @Nullable
        SurvivalEditRecord lastApplied() {
            for (int i = entries.size() - 1; i >= 0; i--) {
                SurvivalEditRecord record = entries.get(i);
                if (!undone.contains(record.id)) {
                    return record;
                }
            }
            return null;
        }

        @Nullable
        SurvivalEditRecord lastUndone() {
            for (int i = redoStack.size() - 1; i >= 0; i--) {
                SurvivalEditRecord record = find(redoStack.get(i));
                if (record != null) {
                    return record;
                }
            }
            return null;
        }
    }
}
