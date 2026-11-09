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

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One stored edit: what changed, what it cost, and where it happened.
 *
 * <p>The file is written so that the summary can be read without touching the blocks. A record is a
 * small header (id, time, world, cost, centre) followed by a gzip-compressed body of palette-encoded
 * block states and delta-encoded positions. Loading a player's history therefore reads a few
 * hundred bytes per edit, and only an undo or a redo that actually needs the blocks decompresses
 * them.</p>
 *
 * <p>Each body entry stores the block that was there before the edit and the block the edit put
 * there. That pair is what makes refunds safe: a block is only refunded when the world still holds
 * exactly the state the edit placed, so a block the player later mined or replaced is not paid back
 * twice.</p>
 */
final class SurvivalEditRecord {

    private static final int MAGIC = 0x53454431; // "SED1"
    private static final int FORMAT = 1;

    final long id;
    final long created;
    final String dimension;
    final boolean paid;
    final int blockCount;
    final double centerX;
    final double centerY;
    final double centerZ;
    /** What the edit was charged, by item. Empty for a free edit. */
    final Map<Item, Long> charged;
    final Path file;

    private final long payloadOffset;
    private @Nullable BlockDiff loaded;

    private SurvivalEditRecord(long id, long created, String dimension, boolean paid, int blockCount,
                               double centerX, double centerY, double centerZ,
                               Map<Item, Long> charged, Path file, long payloadOffset) {
        this.id = id;
        this.created = created;
        this.dimension = dimension;
        this.paid = paid;
        this.blockCount = blockCount;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.charged = charged;
        this.file = file;
        this.payloadOffset = payloadOffset;
    }

    /**
     * Reads the summary of a stored edit.
     *
     * @throws IOException when the file is not a readable edit record
     */
    static SurvivalEditRecord read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a SurvivalEdit record: " + file);
            }
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IOException("unsupported SurvivalEdit record format " + format + ": " + file);
            }
            int metaLength = in.readInt();
            if (metaLength <= 0 || metaLength > 1 << 20) {
                throw new IOException("damaged SurvivalEdit record: " + file);
            }
            byte[] meta = new byte[metaLength];
            in.readFully(meta);

            long payloadOffset = 12L + metaLength;
            try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(meta))) {
                long id = header.readLong();
                long created = header.readLong();
                String dimension = header.readUTF();
                boolean paid = header.readBoolean();
                int blockCount = header.readInt();
                double centerX = header.readDouble();
                double centerY = header.readDouble();
                double centerZ = header.readDouble();
                Map<Item, Long> charged = readLedger(header);
                return new SurvivalEditRecord(id, created, dimension, paid, blockCount,
                    centerX, centerY, centerZ, charged, file, payloadOffset);
            }
        }
    }

    /**
     * Writes an edit to disk and returns its record.
     *
     * <p>The positions, previous states and new states are parallel arrays. They must already be in
     * chunk order, which is the order the edit was applied in.</p>
     */
    static SurvivalEditRecord write(Path file, long id, long created, String dimension, boolean paid,
                                    Map<Item, Long> charged,
                                    BlockVector3[] positions, BaseBlock[] previous, BaseBlock[] next)
        throws IOException {
        int count = positions.length;

        Map<String, Integer> paletteIndex = new HashMap<>();
        List<String> palette = new ArrayList<>();
        int[] previousIds = new int[count];
        int[] nextIds = new int[count];
        for (int i = 0; i < count; i++) {
            previousIds[i] = paletteId(palette, paletteIndex, previous[i]);
            nextIds[i] = paletteId(palette, paletteIndex, next[i]);
        }

        double centerX = 0;
        double centerZ = 0;
        long minY = Long.MAX_VALUE;
        long maxY = Long.MIN_VALUE;
        for (BlockVector3 position : positions) {
            centerX += position.getX();
            centerZ += position.getZ();
            minY = Math.min(minY, position.getY());
            maxY = Math.max(maxY, position.getY());
        }
        centerX = count == 0 ? 0 : centerX / count;
        centerZ = count == 0 ? 0 : centerZ / count;
        double centerY = count == 0 ? 0 : (minY + maxY) / 2.0;

        ByteArrayOutputStream metaBytes = new ByteArrayOutputStream();
        try (DataOutputStream meta = new DataOutputStream(metaBytes)) {
            meta.writeLong(id);
            meta.writeLong(created);
            meta.writeUTF(dimension);
            meta.writeBoolean(paid);
            meta.writeInt(count);
            meta.writeDouble(centerX);
            meta.writeDouble(centerY);
            meta.writeDouble(centerZ);
            writeLedger(meta, charged);
        }
        byte[] meta = metaBytes.toByteArray();

        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(compressedBytes))) {
            out.writeInt(palette.size());
            for (String state : palette) {
                out.writeUTF(state);
            }
            out.writeInt(count);
            long lastX = 0;
            long lastY = 0;
            long lastZ = 0;
            for (int i = 0; i < count; i++) {
                BlockVector3 position = positions[i];
                writeSigned(out, position.getX() - lastX);
                writeSigned(out, position.getY() - lastY);
                writeSigned(out, position.getZ() - lastZ);
                lastX = position.getX();
                lastY = position.getY();
                lastZ = position.getZ();
                writeSigned(out, previousIds[i]);
                writeSigned(out, nextIds[i]);
            }
        }

        byte[] body = compressedBytes.toByteArray();
        long payloadOffset = 12L + meta.length;

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(meta.length);
            out.write(meta);
            out.write(body);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }

        return new SurvivalEditRecord(id, created, dimension, paid, count, centerX, centerY, centerZ,
            new LinkedHashMap<>(charged), file, payloadOffset);
    }

    private static int paletteId(List<String> palette, Map<String, Integer> index, @Nullable BaseBlock block) {
        String state = block == null ? "minecraft:air" : block.getAsString();
        Integer existing = index.get(state);
        if (existing != null) {
            return existing;
        }
        int id = palette.size();
        palette.add(state);
        index.put(state, id);
        return id;
    }

    private static void writeSigned(DataOutputStream out, long value) throws IOException {
        // Zigzag so small negative deltas stay small, then a plain variable-length integer.
        long zigzag = (value << 1) ^ (value >> 63);
        while ((zigzag & ~0x7FL) != 0) {
            out.writeByte((int) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        out.writeByte((int) zigzag);
    }

    private static Map<Item, Long> readLedger(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        Map<Item, Long> ledger = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            String id = in.readUTF();
            long amount = in.readLong();
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(location);
            if (item != null) {
                ledger.put(item, amount);
            }
        }
        return ledger;
    }

    private static void writeLedger(DataOutputStream out, Map<Item, Long> ledger) throws IOException {
        out.writeShort(Math.min(ledger.size(), 0xFFFF));
        int written = 0;
        for (Map.Entry<Item, Long> entry : ledger.entrySet()) {
            if (written++ >= 0xFFFF) {
                break;
            }
            out.writeUTF(BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            out.writeLong(entry.getValue());
        }
    }

    /**
     * The blocks of the edit, loaded on demand.
     */
    BlockDiff blocks() throws IOException {
        BlockDiff diff = loaded;
        if (diff != null) {
            return diff;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(payloadOffset);
            InputStream raw = new InputStream() {
                @Override
                public int read() throws IOException {
                    return raf.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return raf.read(buffer, offset, length);
                }
            };
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
                int paletteSize = in.readInt();
                BaseBlock[] palette = new BaseBlock[paletteSize];
                for (int i = 0; i < paletteSize; i++) {
                    palette[i] = parse(in.readUTF());
                }
                int count = in.readInt();
                if (count < 0) {
                    throw new IOException("damaged SurvivalEdit record: " + file);
                }
                BlockVector3[] positions = new BlockVector3[count];
                BaseBlock[] previous = new BaseBlock[count];
                BaseBlock[] next = new BaseBlock[count];
                long x = 0;
                long y = 0;
                long z = 0;
                for (int i = 0; i < count; i++) {
                    x += readSigned(in);
                    y += readSigned(in);
                    z += readSigned(in);
                    positions[i] = BlockVector3.at(x, y, z);
                    previous[i] = palette[readSignedChecked(in, paletteSize)];
                    next[i] = palette[readSignedChecked(in, paletteSize)];
                }
                diff = new BlockDiff(positions, previous, next);
                loaded = diff;
                return diff;
            }
        } catch (EOFException e) {
            throw new IOException("truncated SurvivalEdit record: " + file, e);
        }
    }

    private static int readSignedChecked(DataInputStream in, int paletteSize) throws IOException {
        int id = (int) readSigned(in);
        if (id < 0 || id >= paletteSize) {
            throw new IOException("damaged palette in SurvivalEdit record");
        }
        return id;
    }

    private static BaseBlock parse(String state) throws IOException {
        ParserContext context = new ParserContext();
        context.setRestricted(false);
        context.setPreferringWildcard(false);
        try {
            return WorldEdit.getInstance().getBlockFactory().parseFromInput(state, context);
        } catch (InputParseException e) {
            throw new IOException("unknown block state in SurvivalEdit record: " + state, e);
        }
    }

    private static long readSigned(DataInputStream in) throws IOException {
        long raw = readVarLong(in);
        return (raw >>> 1) ^ -(raw & 1);
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("varint too long");
            }
        }
    }

    /**
     * Removes this edit from disk.
     */
    void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A stale file is harmless; it is skipped when it cannot be read.
        }
    }

    boolean isSameWorld(String world) {
        return dimension.equals(world);
    }

    /**
     * Distance from a position to the middle of this edit, in blocks.
     */
    double distanceTo(double x, double y, double z) {
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The blocks of an edit, in the order they were applied.
     */
    static final class BlockDiff {

        final BlockVector3[] positions;
        final BaseBlock[] previous;
        final BaseBlock[] next;

        BlockDiff(BlockVector3[] positions, BaseBlock[] previous, BaseBlock[] next) {
            this.positions = positions;
            this.previous = previous;
            this.next = next;
        }

        int size() {
            return positions.length;
        }
    }
}
