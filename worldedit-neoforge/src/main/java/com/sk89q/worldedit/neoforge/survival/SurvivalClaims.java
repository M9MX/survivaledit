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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

/**
 * Remembers which blocks in the world were bought from a player's storage.
 *
 * <p>This is what makes a refund safe. A block only pays its item back when the world still holds
 * exactly the block that was bought, and only once: the claim is spent the moment it is refunded,
 * whether that happens because the player undid the edit or because a later edit pasted something
 * over it.</p>
 *
 * <p>Spent claims are kept too, against the state they were spent on, and are only dropped when that
 * exact block is put back. If it comes back into the world, the command that puts it back has to
 * pay for it, so a refund cannot be turned into a free block by covering a bought block and then
 * undoing the cover.</p>
 *
 * <p>The ledger is stored per world, next to the edit history, and saved after every edit. Losing it
 * only costs refunds, never items: a block without a claim is treated as one the player placed by
 * hand, and is voided when it is removed.</p>
 */
final class SurvivalClaims {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAGIC = 0x53454331; // "SEC1"
    private static final int FORMAT = 3;

    private static @Nullable SurvivalClaims instance;

    private final Path root;
    private final Map<String, World> worlds = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    private SurvivalClaims(Path root) {
        this.root = root;
    }

    static void install(Path claimsDir) {
        instance = new SurvivalClaims(claimsDir);
    }

    static @Nullable SurvivalClaims get() {
        return instance;
    }

    /**
     * One bought block: who paid for it, with what, and which state it was.
     *
     * <p>A block can be paid with several items: a copycat is the copycat plus the block it is
     * dressed up as, and a candle block of four candles is four candles. Everything that was paid
     * has to come back, so a claim keeps every item, not just one.</p>
     */
    static final class Claim {

        /** One item of a claim, with where it came from so it can be returned there. */
        static final class Entry {

            final Item item;
            final long count;
            final String source;

            Entry(Item item, long count, String source) {
                this.item = item;
                this.count = count;
                this.source = source;
            }
        }

        final UUID owner;
        final List<Entry> items;
        final int state;

        Claim(UUID owner, List<Entry> items, int state) {
            this.owner = owner;
            this.items = items;
            this.state = state;
        }

        /** The items of this claim, by item. */
        Map<Item, Long> amounts() {
            Map<Item, Long> amounts = new java.util.LinkedHashMap<>();
            for (Entry entry : items) {
                amounts.merge(entry.item, entry.count, Long::sum);
            }
            return amounts;
        }

        /** The first source named for each item, for putting it back. */
        Map<Item, String> sources() {
            Map<Item, String> sources = new java.util.LinkedHashMap<>();
            for (Entry entry : items) {
                sources.putIfAbsent(entry.item, entry.source);
            }
            return sources;
        }
    }

    /**
     * Refunds the block that was standing at this position, if it was bought and is still there.
     *
     * <p>The claim is moved to the spent side, so the same block can never be refunded twice. A
     * claim that no longer matches the block in the world is dropped: the block was mined, replaced
     * or blown up in the meantime, and the player already has whatever came out of it.</p>
     *
     * @return the claim that may be paid back, or {@code null}
     */
    @Nullable
    Claim takeRefund(String dimension, long position, int state) {
        World world = world(dimension);
        Claim claim = world.live.get(position);
        if (claim == null) {
            return null;
        }
        world.live.remove(position);
        touch(dimension);
        if (claim.state != state) {
            return null;
        }
        world.spent.put(position, claim);
        return claim;
    }

    /**
     * Takes the spent claim for a block that is being put back into the world.
     *
     * <p>Such a block has already been paid back once, so the command that restores it has to pay
     * for it again. When this returns a claim, the caller owns the block from now on.</p>
     */
    @Nullable
    Claim unspend(String dimension, long position, int state) {
        World world = world(dimension);
        Claim claim = world.spent.get(position);
        if (claim == null || claim.state != state) {
            return null;
        }
        world.spent.remove(position);
        touch(dimension);
        return claim;
    }

    /**
     * Whether this exact block is still owned by a player, so removing it pays items back.
     */
    boolean hasLiveClaim(String dimension, long position, int state) {
        Claim claim = world(dimension).live.get(position);
        return claim != null && claim.state == state;
    }

    /**
     * Whether this exact block was refunded at this position before.
     */
    boolean isSpent(String dimension, long position, int state) {
        Claim claim = world(dimension).spent.get(position);
        return claim != null && claim.state == state;
    }

    /**
     * Records that the block now standing at this position was bought by the given player.
     */
    void claim(String dimension, long position, int state, UUID owner, Map<Item, Long> items,
               Map<Item, String> sources) {
        List<Claim.Entry> entries = new ArrayList<>();
        for (Map.Entry<Item, Long> entry : items.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            entries.add(new Claim.Entry(entry.getKey(), entry.getValue(),
                sources.getOrDefault(entry.getKey(), "inventory")));
        }
        if (entries.isEmpty()) {
            return;
        }
        world(dimension).live.put(position, new Claim(owner, entries, state));
        touch(dimension);
    }

    /**
     * Saves every world that changed since the last save.
     */
    void save() {
        if (dirty.isEmpty()) {
            return;
        }
        List<String> changed = new ArrayList<>(dirty);
        dirty.clear();
        for (String dimension : changed) {
            save(dimension);
        }
    }

    /**
     * Saves everything, used when the server stops.
     */
    void saveAll() {
        dirty.clear();
        for (String dimension : new ArrayList<>(worlds.keySet())) {
            save(dimension);
        }
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private World world(String dimension) {
        World world = worlds.get(dimension);
        if (world == null) {
            world = new World();
            worlds.put(dimension, world);
        }
        if (!world.loaded) {
            world.loaded = true;
            load(dimension, world);
        }
        return world;
    }

    private void touch(String dimension) {
        dirty.add(dimension);
    }

    private Path file(String dimension) {
        return root.resolve(dimension.replace(':', '_').replace('/', '_') + ".dat");
    }

    private void load(String dimension, World world) {
        Path file = file(dimension);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
            new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a SurvivalEdit claim file: " + file);
            }
            int format = in.readInt();
            if (format != FORMAT && format != 2 && format != 1) {
                throw new IOException("unsupported SurvivalEdit claim format " + format + ": " + file);
            }
            readClaims(in, world.live, format);
            readClaims(in, world.spent, format);
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not read block ownership in {}", file, e);
        }
    }

    private static void readClaims(DataInputStream in, Map<Long, Claim> into, int format) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            long position = in.readLong();
            int state = in.readInt();
            UUID owner = new UUID(in.readLong(), in.readLong());
            List<Claim.Entry> entries = new ArrayList<>();
            if (format >= 3) {
                int items = in.readInt();
                for (int j = 0; j < items; j++) {
                    Item item = item(in.readUTF());
                    long amount = in.readLong();
                    String source = in.readUTF();
                    if (item != null && amount > 0) {
                        entries.add(new Claim.Entry(item, amount, source));
                    }
                }
            } else {
                Item item = item(in.readUTF());
                String source = format >= 2 ? in.readUTF() : "inventory";
                if (item != null) {
                    entries.add(new Claim.Entry(item, 1, source));
                }
            }
            if (!entries.isEmpty()) {
                into.put(position, new Claim(owner, entries, state));
            }
        }
    }

    private void save(String dimension) {
        World world = worlds.get(dimension);
        if (world == null) {
            return;
        }
        Path file = file(dimension);
        try {
            Files.createDirectories(root);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                writeClaims(out, world.live);
                writeClaims(out, world.spent);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("SurvivalEdit: could not save block ownership for {}", dimension, e);
        }
    }

    private static void writeClaims(DataOutputStream out, Map<Long, Claim> claims) throws IOException {
        out.writeInt(claims.size());
        for (Map.Entry<Long, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            out.writeLong(entry.getKey());
            out.writeInt(claim.state);
            out.writeLong(claim.owner.getMostSignificantBits());
            out.writeLong(claim.owner.getLeastSignificantBits());
            out.writeInt(claim.items.size());
            for (Claim.Entry item : claim.items) {
                out.writeUTF(BuiltInRegistries.ITEM.getKey(item.item).toString());
                out.writeLong(item.count);
                out.writeUTF(item.source);
            }
        }
    }

    private static @Nullable Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : BuiltInRegistries.ITEM.get(location);
    }

    /**
     * The claims of one world.
     */
    private static final class World {

        /** Blocks standing in the world that were bought and not refunded yet. */
        final Map<Long, Claim> live = new HashMap<>();
        /** Blocks that were refunded where they stood, so restoring them costs items again. */
        final Map<Long, Claim> spent = new HashMap<>();
        boolean loaded;
    }
}
