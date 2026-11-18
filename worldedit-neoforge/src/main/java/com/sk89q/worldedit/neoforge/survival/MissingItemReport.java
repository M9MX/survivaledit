package com.sk89q.worldedit.neoforge.survival;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/**
 * Stores the most recent missing-item list and prints it in compact chat pages.
 */
public final class MissingItemReport {
    private static final int CHAT_TYPES_PER_LINE = 6;
    private static final int DIRECT_CHAT_TYPES = 200;
    private static final int PAGE_TYPES = 12;
    private static final Map<UUID, Map<Item, Long>> REPORTS = new HashMap<>();

    private MissingItemReport() {
    }

    public static void report(ServerPlayer player, Map<Item, Long> missing) {
        if (missing.isEmpty()) {
            REPORTS.remove(player.getUUID());
            return;
        }

        Map<Item, Long> sorted = new java.util.LinkedHashMap<>();
        missing.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        REPORTS.put(player.getUUID(), sorted);

        List<Map.Entry<Item, Long>> entries = new ArrayList<>(sorted.entrySet());
        if (entries.size() <= DIRECT_CHAT_TYPES) {
            printDirect(player, entries);
        } else {
            player.sendSystemMessage(Component.literal(
                "Missing " + entries.size() + " item types."));
            printPage(player, entries.subList(0, Math.min(PAGE_TYPES, entries.size())),
                1, pageCount(entries.size()));
            player.sendSystemMessage(Component.literal(
                "Use //payment missing 2 for the next page."));
        }
    }

    public static int show(CommandContext<CommandSourceStack> context, int page) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        Map<Item, Long> missing = REPORTS.get(player.getUUID());
        if (missing == null || missing.isEmpty()) {
            player.sendSystemMessage(Component.literal("No missing-item report is available."));
            return 0;
        }

        List<Map.Entry<Item, Long>> entries = new ArrayList<>(missing.entrySet());
        int pages = pageCount(entries.size());
        int selected = Math.max(1, Math.min(page, pages));
        int start = (selected - 1) * PAGE_TYPES;
        int end = Math.min(entries.size(), start + PAGE_TYPES);
        printPage(player, entries.subList(start, end), selected, pages);
        return 1;
    }

    private static int pageCount(int size) {
        return Math.max(1, (size + PAGE_TYPES - 1) / PAGE_TYPES);
    }

    private static void printPage(ServerPlayer player,
                                  List<Map.Entry<Item, Long>> entries,
                                  int page, int pages) {
        if (pages > 1) {
            player.sendSystemMessage(Component.literal(
                "Missing items page " + page + "/" + pages + ":"));
        }
        for (int start = 0; start < entries.size(); start += CHAT_TYPES_PER_LINE) {
            int end = Math.min(entries.size(), start + CHAT_TYPES_PER_LINE);
            MutableComponent line = Component.literal("Missing:");
            for (int i = start; i < end; i++) {
                Map.Entry<Item, Long> entry = entries.get(i);
                line.append(i == start ? " " : ", ")
                    .append(entry.getKey().getName(new ItemStack(entry.getKey())).getString())
                    .append(" x")
                    .append(NumberFormatting.hoverCount(entry.getValue()));
            }
            player.sendSystemMessage(line);
        }
    }

    private static void printDirect(ServerPlayer player,
                                    List<Map.Entry<Item, Long>> entries) {
        printPage(player, entries, 1, 1);
    }
}
