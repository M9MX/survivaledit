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

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads a player's Curios slots without depending on Curios at compile time.
 *
 * <p>Curios is only needed so that a wireless terminal carried in a Curios slot can be found. Every
 * lookup is guarded, so the mod works exactly the same when Curios is not installed or when its API
 * changes shape between versions.</p>
 */
final class CuriosSlots {

    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";

    private CuriosSlots() {
    }

    static List<ItemStack> getCurioStacks(ServerPlayer player) {
        try {
            Class<?> api = Class.forName(CURIOS_API);
            Object handler = inventory(api, player);
            if (handler == null) {
                return Collections.emptyList();
            }

            List<ItemStack> stacks = new ArrayList<>();
            collect(handler, "getCurios", stacks);
            collect(handler, "getCosmeticStacks", stacks);
            return stacks;
        } catch (Throwable ignored) {
            // Curios is optional, and reflection against an unknown version must never break an edit.
            return Collections.emptyList();
        }
    }

    private static Object inventory(Class<?> api, ServerPlayer player) throws Exception {
        Method method = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
        Object result = method.invoke(null, player);
        if (result == null) {
            return null;
        }
        // Newer Curios returns Optional, while older releases returned a LazyOptional.
        try {
            Method resolve = result.getClass().getMethod("resolve");
            Object unwrapped = resolve.invoke(result);
            if (unwrapped != null) {
                return unwrapped;
            }
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method orElse = result.getClass().getMethod("orElse", Object.class);
            Object unwrapped = orElse.invoke(result, (Object) null);
            if (unwrapped != null) {
                return unwrapped;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object handler, String methodName, List<ItemStack> out) throws Exception {
        Object value;
        try {
            value = handler.getClass().getMethod(methodName).invoke(handler);
        } catch (NoSuchMethodException e) {
            return;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (Object slotHandler : map.values()) {
            Object stackHandler = slotHandler.getClass().getMethod("getStacks").invoke(slotHandler);
            int slots = (int) stackHandler.getClass().getMethod("getSlots").invoke(stackHandler);
            Method getStack = stackHandler.getClass().getMethod("getStackInSlot", int.class);
            for (int slot = 0; slot < slots; slot++) {
                Object stack = getStack.invoke(stackHandler, slot);
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    out.add(itemStack);
                }
            }
        }
    }
}
