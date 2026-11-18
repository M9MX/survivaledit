/*
 * SurvivalEdit, a WorldEdit fork tuned for survival play
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sk89q.worldedit.neoforge.survival;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Commands for selecting and smoke-testing survival payment providers.
 */
public final class PaymentCommands {
    private PaymentCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("/payment")
            .executes(PaymentCommands::status)
            .then(literal("test")
                .executes(PaymentCommands::smokeTestNoItem))
            .then(literal("missing")
                .executes(context -> MissingItemReport.show(context, 1))
                .then(argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                    .executes(context -> MissingItemReport.show(context,
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page")))))
            .then(argument("mode", StringArgumentType.word())
                .suggests(modeSuggestions())
                .executes(PaymentCommands::changeMode))
        );
    }

    private static SuggestionProvider<CommandSourceStack> modeSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            List<String> modes = new java.util.ArrayList<>(List.of("status", "test", "missing", "auto"));
            for (PaymentProvider provider : PaymentProviders.providers()) {
                modes.add(provider.id());
            }
            for (String mode : modes) {
                if (mode.startsWith(remaining)) {
                    builder.suggest(mode);
                }
            }
            return builder.buildFuture();
        };
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        player.sendSystemMessage(Component.literal(
            "Survival payment mode: " + PaymentProviders.status(player)));
        return 1;
    }

    private static int changeMode(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        String mode = StringArgumentType.getString(context, "mode").toLowerCase();
        if ("status".equals(mode)) {
            return status(context);
        }
        if ("test".equals(mode) || "smoke".equals(mode)) {
            return smokeTest(player, context);
        }
        if (!PaymentProviders.setPreference(mode)) {
            context.getSource().sendFailure(Component.literal(
                "Unknown payment provider '" + mode + "'. Available providers: "
                    + providerIds()));
            return 0;
        }

        PaymentProvider selected = PaymentProviders.provider(mode);
        if (selected == null) {
            player.sendSystemMessage(Component.literal("Payment mode set to auto."));
            return 1;
        }
        if (!selected.available(player) && !"inventory".equals(mode)) {
            context.getSource().sendFailure(Component.literal(
                selected.displayName() + " is not available to this player."));
            PaymentProviders.setPreference("auto");
            return 0;
        }
        player.sendSystemMessage(Component.literal(
            "Survival payment mode set to " + selected.displayName() + "."));
        return 1;
    }

    private static int smokeTest(ServerPlayer player, CommandContext<CommandSourceStack> context) {
        return smokeTestNoItem(context);
    }

    private static int smokeTestNoItem(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        player.sendSystemMessage(Component.literal("Survival payment smoke test:"));
        for (PaymentProvider provider : PaymentProviders.providers()) {
            boolean available = provider.available(player);
            String detail = available ? "available" : "not available";
            long count = -1;
            if (available) {
                try {
                    ResourceStock stock = provider.open(player);
                    if (stock != null) {
                        detail += ", stock read test passed";
                        count = stock.itemCount();
                        if (stock instanceof ChestStock chestStock) {
                            detail += ", containers=" + chestStock.containerCount();
                        }
                    } else {
                        detail = "provider is available but returned no stock";
                    }
                } catch (Throwable t) {
                    detail = "stock read failed: " + t.getClass().getSimpleName();
                }
            }
            MutableComponent line = Component.literal("- " + provider.displayName() + ": " + detail);
            if (count >= 0) {
                line.append(", items=").append(NumberFormatting.hoverCount(count));
            }
            player.sendSystemMessage(line);
        }
        player.sendSystemMessage(Component.literal(
            "- Mode: " + PaymentProviders.status(player)));
        player.sendSystemMessage(Component.literal(
            "- Block: " + (player.level().getBlockState(player.blockPosition()).getBlock()
                .getName().getString())));
        player.sendSystemMessage(Component.literal(
            "- History and world ownership were not modified"));
        return 1;
    }

    private static String providerIds() {
        StringBuilder result = new StringBuilder("auto");
        for (PaymentProvider provider : PaymentProviders.providers()) {
            result.append(", ").append(provider.id());
        }
        return result.toString();
    }
}
