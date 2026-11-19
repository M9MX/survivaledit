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

package com.sk89q.worldedit.neoforge.mixin;

import com.sk89q.worldedit.neoforge.ToolUseDelay;
import com.sk89q.worldedit.neoforge.item.WorldEditTools;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the hold-to-use delay for SurvivalEdit tools.
 *
 * <p>{@code Minecraft.startUseItem} hard-codes 4 ticks for every item. Holding a brush at that
 * rate feels slow, so a tool uses the server's configured delay instead; 0 makes it repeat every
 * tick.</p>
 */
@Mixin(Minecraft.class)
public class MixinMinecraftToolUseDelay {

    @Shadow
    private int rightClickDelay;

    @Inject(
        method = "startUseItem",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;rightClickDelay:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        )
    )
    private void survivaledit$useToolDelay(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.player == null || !WorldEditTools.isTool(minecraft.player.getMainHandItem())) {
            return;
        }
        this.rightClickDelay = ToolUseDelay.clientTicks();
    }
}
