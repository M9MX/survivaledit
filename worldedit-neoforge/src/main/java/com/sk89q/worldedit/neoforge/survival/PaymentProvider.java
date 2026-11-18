package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;

/** A storage implementation that can pay for and refund survival edits. */
public interface PaymentProvider {
    String id();
    String displayName();
    boolean available(ServerPlayer player);
    @Nullable ResourceStock open(ServerPlayer player);
}
