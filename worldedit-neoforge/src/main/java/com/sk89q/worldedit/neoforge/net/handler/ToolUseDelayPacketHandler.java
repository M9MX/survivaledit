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

package com.sk89q.worldedit.neoforge.net.handler;

import com.sk89q.worldedit.neoforge.NeoForgeWorldEdit;
import com.sk89q.worldedit.neoforge.ToolUseDelay;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Sends the server's tool use delay to the client, where the held right click is timed.
 */
public final class ToolUseDelayPacketHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final ResourceLocation IDENTIFIER =
        ResourceLocation.fromNamespaceAndPath(NeoForgeWorldEdit.MOD_ID, "tool_use_delay");

    public record ToolUseDelayPacket(int ticks) implements CustomPacketPayload {
        public static final Type<ToolUseDelayPacket> TYPE = new Type<>(IDENTIFIER);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private ToolUseDelayPacketHandler() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
            .optional()
            .playToClient(
                ToolUseDelayPacket.TYPE,
                CustomPacketPayload.codec(
                    (packet, buffer) -> buffer.writeVarInt(packet.ticks()),
                    buffer -> new ToolUseDelayPacket(buffer.readVarInt())
                ),
                (payload, context) -> context.enqueueWork(() -> ToolUseDelay.setClientTicks(payload.ticks()))
            );
    }
}
