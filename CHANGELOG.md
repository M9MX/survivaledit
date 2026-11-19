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
- Edits are now charged for the material a copycat or framed block mimics, in addition to the
  copycat itself.
- Edits are now charged per item of a block state; four candles cost four candles, not one.
- Stored edits now keep block entity data in binary form instead of a text form, so modded block
  data such as Copycats+ material data survives into undo and redo.
- Removing a bought copycat or framed block with an edit or an undo no longer drops its material on
  the ground in addition to the refund, which duplicated the material.
- Added `survival.message-threshold`: edits below it are silent, including their refund and
  undo/redo lines. Only errors are always shown.
- Edited blocks now notify their neighbours the way vanilla does, so fences, walls, and panes
  reconnect and support-dependent blocks react to the change.
- Block ownership records keep every item a bought block was paid with, so undo returns the whole
  price. Older claim files still load.
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
