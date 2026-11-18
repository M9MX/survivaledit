# SurvivalEdit changelog

This file records SurvivalEdit changes. The upstream WorldEdit history remains in
[`CHANGELOG.txt`](CHANGELOG.txt).

## Unreleased

### Changed

- Reworked the project documentation for public NeoForge releases.
- Removed eager generation of every registered block state during WorldEdit platform setup.
- Added lazy NeoForge block-state ID registration to reduce startup allocations in large modpacks.

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
