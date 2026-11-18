# Known issues and compatibility notes

- SurvivalEdit supports NeoForge on Minecraft `1.21.1`; other loaders and Minecraft versions are
  not supported by this project.
- The official WorldEdit mod must not be installed alongside SurvivalEdit because both use the
  `worldedit` mod ID.
- Survival payment depends on Applied Energistics 2 and AE2WTLib.
- Large paid edits are intentionally applied gradually and may take time to finish.
- Schematic use in survival is disabled by default and should be tested before enabling it in a
  modpack.
- Modded block entities and multiblock structures should be protected unless their behavior has
  been tested in the target pack.
- Block entities with inventory-like data are refused when a paid edit would overwrite them.
- `/stop` finalizes queued edits instead of continuing to place blocks after shutdown begins.
- Optional provider API mismatches are reported by `//payment test` and do not stop inventory payment.
- Payment preview checks the next paid operation only and consumes the preview toggle after one edit.
- Nearby-container payment includes every block entity implementing `Container`; verify modded
  machine and storage inventories before using `//payment chests` in that modpack.

When reporting a problem, include the Minecraft version, NeoForge version, SurvivalEdit version,
modpack name and version, relevant configuration, exact command, and the complete log excerpt.
