package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;

final class InventoryPaymentProvider implements PaymentProvider {
    @Override public String id() { return "inventory"; }
    @Override public String displayName() { return "Player inventory"; }
    @Override public boolean available(ServerPlayer player) { return player != null && player.isAlive(); }
    @Override public ResourceStock open(ServerPlayer player) { return new InventoryStock(player); }
}
