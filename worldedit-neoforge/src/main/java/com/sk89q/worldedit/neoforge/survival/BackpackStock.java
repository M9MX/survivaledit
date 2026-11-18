package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ResourceStock adapter for a Sophisticated Backpacks inventory handler. */
final class BackpackStock implements ResourceStock {
    private final List<Object> handlers;

    BackpackStock(List<Object> handlers) {
        this.handlers = handlers;
    }

    @Override
    public long available(Item item) {
        long total = 0;
        for (Object handler : handlers) {
            try {
                Method getSlots = handler.getClass().getMethod("getSlots");
                Method getStack = handler.getClass().getMethod("getStackInSlot", int.class);
                int slots = (Integer) getSlots.invoke(handler);
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack stack = (ItemStack) getStack.invoke(handler, slot);
                    if (!stack.isEmpty() && stack.is(item)) {
                        total += stack.getCount();
                    }
                }
            } catch (Throwable ignored) {
                // An incompatible backpack API is ignored by this provider instance.
            }
        }
        return total;
    }

    @Override
    public boolean take(Map<Item, Long> amounts) {
        Map<Object, Map<Integer, ItemStack>> extracted = new LinkedHashMap<>();

        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            if (available(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            for (Object handler : handlers) {
                if (remaining <= 0) {
                    break;
                }
                remaining = takeFromHandler(handler, entry.getKey(), remaining, extracted);
            }
            if (remaining > 0) {
                rollback(extracted);
                return false;
            }
        }
        return true;
    }

    private long takeFromHandler(Object handler, Item item, long amount,
                                 Map<Object, Map<Integer, ItemStack>> extracted) {
        long remaining = amount;
        try {
            Method getSlots = handler.getClass().getMethod("getSlots");
            Method extract = handler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
            int slots = (Integer) getSlots.invoke(handler);
            for (int slot = 0; slot < slots && remaining > 0; slot++) {
                ItemStack taken = (ItemStack) extract.invoke(handler, slot, (int) Math.min(remaining, Integer.MAX_VALUE), false);
                if (!taken.isEmpty() && taken.is(item)) {
                    remaining -= taken.getCount();
                    extracted.computeIfAbsent(handler, ignored -> new LinkedHashMap<>())
                        .merge(slot, taken.copy(), (a, b) -> {
                            a.grow(b.getCount());
                            return a;
                        });
                }
            }
        } catch (Throwable ignored) {
            return remaining;
        }
        return remaining;
    }

    private void rollback(Map<Object, Map<Integer, ItemStack>> extracted) {
        for (Map.Entry<Object, Map<Integer, ItemStack>> handlerEntry : extracted.entrySet()) {
            Object handler = handlerEntry.getKey();
            for (ItemStack stack : handlerEntry.getValue().values()) {
                insert(handler, stack);
            }
        }
    }

    @Override
    public Map<Item, Long> give(Map<Item, Long> amounts) {
        Map<Item, Long> leftover = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            int maxStack = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            for (Object handler : handlers) {
                if (remaining <= 0) {
                    break;
                }
                remaining = giveToHandler(handler, entry.getKey(), remaining, maxStack);
            }
            if (remaining > 0) {
                leftover.put(entry.getKey(), remaining);
            }
        }
        return leftover;
    }

    private long giveToHandler(Object handler, Item item, long amount, int maxStack) {
        long remaining = amount;
        try {
            Method insert = handler.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
            Method getSlots = handler.getClass().getMethod("getSlots");
            int slots = (Integer) getSlots.invoke(handler);
            while (remaining > 0) {
                int size = (int) Math.min(remaining, maxStack);
                ItemStack stack = new ItemStack(item, size);
                ItemStack returned = stack;
                for (int slot = 0; slot < slots; slot++) {
                    returned = (ItemStack) insert.invoke(handler, slot, stack, false);
                    if (returned.isEmpty()) {
                        remaining -= size;
                        break;
                    }
                    stack = returned;
                }
                if (returned == stack && !returned.isEmpty()) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            return remaining;
        }
        return remaining;
    }

    private void insert(Object handler, ItemStack stack) {
        try {
            Method insert = handler.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class);
            Method getSlots = handler.getClass().getMethod("getSlots");
            int slots = (Integer) getSlots.invoke(handler);
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < slots && !remaining.isEmpty(); slot++) {
                remaining = (ItemStack) insert.invoke(handler, slot, remaining, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
