package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Uses chest-like containers within two blocks of the player.
 */
final class ChestPaymentProvider implements PaymentProvider {
    public static final int RADIUS = 2;

    @Override public String id() { return "chests"; }
    @Override public String displayName() { return "Nearby chests"; }

    @Override
    public boolean available(ServerPlayer player) {
        return open(player) != null;
    }

    @Override
    public @Nullable ResourceStock open(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }

        List<Container> containers = new ArrayList<>();
        BlockPos center = player.blockPosition();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof Container container) {
                        containers.add(container);
                    }
                }
            }
        }
        return containers.isEmpty() ? null : new ChestStock(containers);
    }
}
