package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Optional Sophisticated Backpacks integration.
 *
 * <p>It intentionally uses reflection and no compile-time dependency. Older, missing, or changed
 * versions of the mod simply make this provider unavailable.</p>
 */
final class SophisticatedBackpacksPaymentProvider implements PaymentProvider {
    private static final String MOD_ID = "sophisticatedbackpacks";
    private static final String WRAPPER = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";

    @Override public String id() { return "backpacks"; }
    @Override public String displayName() { return "Sophisticated Backpacks"; }

    @Override
    public boolean available(ServerPlayer player) {
        return modLoaded() && !openStacks(player).isEmpty();
    }

    @Override
    public @Nullable ResourceStock open(ServerPlayer player) {
        if (!modLoaded()) {
            return null;
        }
        try {
            List<Object> handlers = openHandlers(player);
            return handlers.isEmpty() ? null : new BackpackStock(handlers);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean modLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(MOD_ID);
    }

    private static List<ItemStack> openStacks(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        add(stacks, player.getMainHandItem());
        add(stacks, player.getOffhandItem());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            add(stacks, player.getInventory().getItem(slot));
        }
        for (ItemStack stack : CuriosSlots.getCurioStacks(player)) {
            add(stacks, stack);
        }
        return stacks;
    }

    private static void add(List<ItemStack> stacks, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (MOD_ID.equals(key.getNamespace())
            || stack.getItem().getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedbackpacks")) {
            stacks.add(stack);
        }
    }

    private static List<Object> openHandlers(ServerPlayer player) throws ReflectiveOperationException {
        List<Object> handlers = new ArrayList<>();
        Class<?> wrapperClass = Class.forName(WRAPPER);
        Method fromStack = wrapperClass.getMethod("fromStack", ItemStack.class);
        Method getInventoryHandler = null;

        for (ItemStack stack : openStacks(player)) {
            Object wrapper = fromStack.invoke(null, stack);
            if (wrapper == null) {
                continue;
            }
            if (getInventoryHandler == null) {
                getInventoryHandler = wrapper.getClass().getMethod("getInventoryHandler");
            }
            Object handler = getInventoryHandler.invoke(wrapper);
            if (handler != null) {
                handlers.add(handler);
            }
        }
        return handlers;
    }
}
