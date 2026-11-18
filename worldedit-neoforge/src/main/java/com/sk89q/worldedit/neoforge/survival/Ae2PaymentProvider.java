package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;

final class Ae2PaymentProvider implements PaymentProvider {
    @Override public String id() { return "ae2"; }
    @Override public String displayName() { return "Applied Energistics 2"; }
    @Override public boolean available(ServerPlayer player) {
        if (!SurvivalMode.ae2Loaded()) {
            return false;
        }
        try {
            return Ae2Integration.hasGrid(player);
        } catch (Throwable ignored) {
            return false;
        }
    }
    @Override public @Nullable ResourceStock open(ServerPlayer player) {
        if (!SurvivalMode.ae2Loaded()) {
            return null;
        }
        try {
            return Ae2Integration.openStock(player);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
