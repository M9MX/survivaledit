package com.sk89q.worldedit.neoforge.survival;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ResourceStock backed by nearby chest-like containers. */
final class ChestStock implements ResourceStock {
    private final List<Container> containers;

    ChestStock(List<Container> containers) {
        this.containers = List.copyOf(containers);
    }

    int containerCount() {
        return containers.size();
    }

    @Override
    public long itemCount() {
        long total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }


    @Override
    public long available(Item item) {
        long total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    @Override
    public boolean take(Map<Item, Long> amounts) {
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            if (available(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        Map<Container, Map<Integer, ItemStack>> removed = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            for (Container container : containers) {
                if (remaining <= 0) {
                    break;
                }
                for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (stack.isEmpty() || !stack.is(entry.getKey())) {
                        continue;
                    }
                    int count = (int) Math.min(stack.getCount(), remaining);
                    ItemStack taken = container.removeItem(slot, count);
                    if (!taken.isEmpty()) {
                        remaining -= taken.getCount();
                        removed.computeIfAbsent(container, ignored -> new LinkedHashMap<>())
                            .merge(slot, taken.copy(), (a, b) -> {
                                a.grow(b.getCount());
                                return a;
                            });
                    }
                }
            }
            if (remaining > 0) {
                rollback(removed);
                return false;
            }
        }
        for (Container container : containers) {
            container.setChanged();
        }
        return true;
    }

    private void rollback(Map<Container, Map<Integer, ItemStack>> removed) {
        for (Map.Entry<Container, Map<Integer, ItemStack>> containerEntry : removed.entrySet()) {
            Container container = containerEntry.getKey();
            for (Map.Entry<Integer, ItemStack> slotEntry : containerEntry.getValue().entrySet()) {
                ItemStack existing = container.getItem(slotEntry.getKey());
                ItemStack restored = slotEntry.getValue();
                if (existing.isEmpty()) {
                    container.setItem(slotEntry.getKey(), restored);
                } else if (existing.is(restored.getItem())) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    if (space > 0) {
                        int amount = Math.min(space, restored.getCount());
                        existing.grow(amount);
                        restored.shrink(amount);
                    }
                    if (!restored.isEmpty()) {
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            if (container.getItem(slot).isEmpty()) {
                                container.setItem(slot, restored);
                                break;
                            }
                        }
                    }
                }
            }
            container.setChanged();
        }
    }

    @Override
    public Map<Item, Long> give(Map<Item, Long> amounts) {
        Map<Item, Long> leftover = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : amounts.entrySet()) {
            long remaining = entry.getValue();
            int maxStack = Math.max(1, new ItemStack(entry.getKey()).getMaxStackSize());
            for (Container container : containers) {
                if (remaining <= 0) {
                    break;
                }

                for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty() && stack.is(entry.getKey())) {
                        int space = stack.getMaxStackSize() - stack.getCount();
                        if (space > 0) {
                            int amount = (int) Math.min(space, remaining);
                            stack.grow(amount);
                            remaining -= amount;
                        }
                    }
                }
                for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                    if (!container.getItem(slot).isEmpty()) {
                        continue;
                    }
                    int amount = (int) Math.min(maxStack, remaining);
                    ItemStack stack = new ItemStack(entry.getKey(), amount);
                    if (container.canPlaceItem(slot, stack)) {
                        container.setItem(slot, stack);
                        remaining -= amount;
                    }
                }
            }
            if (remaining > 0) {
                leftover.put(entry.getKey(), remaining);
            }
        }
        for (Container container : containers) {
            container.setChanged();
        }
        return leftover;
    }
}
