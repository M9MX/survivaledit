package com.sk89q.worldedit.neoforge.survival;

import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reports optional integration compatibility without allowing provider failures to stop the mod.
 */
public final class CompatibilityGuard {
    private static final Logger LOGGER = LogManager.getLogger();

    private CompatibilityGuard() {
    }

    public static void check() {
        ModList modList = ModList.get();
        if (modList == null) {
            LOGGER.warn("SurvivalEdit: mod metadata is unavailable; optional providers will be disabled.");
            return;
        }

        checkProvider(modList, "ae2", "Applied Energistics 2");
        checkProvider(modList, "ae2wtlib", "AE2WTLib");
        checkProvider(modList, "sophisticatedbackpacks", "Sophisticated Backpacks");
        checkProvider(modList, "sophisticatedcore", "Sophisticated Core");
        LOGGER.info("SurvivalEdit: optional payment integrations checked; inventory payment remains available.");
    }

    private static void checkProvider(ModList modList, String modId, String name) {
        if (!modList.isLoaded(modId)) {
            LOGGER.info("SurvivalEdit: optional provider '{}' is not installed.", name);
            return;
        }
        modList.getModContainerById(modId).ifPresentOrElse(
            container -> LOGGER.info("SurvivalEdit: optional provider '{}' is present as version {}.",
                name, container.getModInfo().getVersion()),
            () -> LOGGER.warn("SurvivalEdit: '{}' is loaded but its mod container is unavailable.", name)
        );
    }
}
