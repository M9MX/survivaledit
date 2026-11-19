# SurvivalEdit changelog

This file records SurvivalEdit changes. The upstream WorldEdit history remains in
[`CHANGELOG.txt`](CHANGELOG.txt).

## Unreleased

### Changed

- Reworked the project documentation for public NeoForge releases.
- Removed eager generation of every registered block state during WorldEdit platform setup.
- Added lazy NeoForge block-state ID registration to reduce startup allocations in large modpacks.
- Added a payment-provider interface for AE2 and inventory storage.
- Added `//payment status`, `//payment test`, and provider selection through `//payment <provider>`.
- Made AE2 and AE2WTLib optional; player inventory remains available as a payment source.
- Added optional Sophisticated Backpacks and Sophisticated Core integration.
- Added nearby chest/container payment within a 2-block radius, including modded `Container`
  block entities.
- Protected paid edits from overwriting block entities that contain item or inventory data.
- Copy, paste, and undo now carry the appearance data of Create copycats, Copycats+, and
  FramedBlocks, so copied facades keep the block they mimic.
- Improved shutdown finalization for queued edits.

### SurvivalEdit functionality

- Added resource-paid survival editing through AE2 and player inventory.
- Added gradual chunk-based edit application.
- Added persistent per-world history with paid undo and redo.
- Added protection rules for blocks, mod namespaces, and block tags.
- Added the `worldedit:axe` item and recipe.

## Release notes policy

Each published version should include:

- supported Minecraft and NeoForge versions;
- required dependency versions;
- user-visible behavior changes;
- removed or incompatible behavior;
- known migration or configuration changes.
