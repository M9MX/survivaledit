<h1>
    <img src="worldedit-logo.svg" alt="SurvivalEdit" width="400" />
</h1>

**Survival-friendly WorldEdit fork for NeoForge.**

This is a personal fork of WorldEdit (NeoForge) tuned for survival play. Edits are paid for out of an
Applied Energistics 2 network and are applied gradually so large edits do not stall the server.

The mod is published under the `worldedit` mod id so it keeps compatibility with WorldEditCUI. The
mod drawer name stays **SurvivalEdit**.

### What is included here
- Selection Axe item (`worldedit:axe`) with lang, model, and texture
- Axe recipe under the `worldedit` namespace
- Survival-edit chat feedback and item usage behavior
- NeoForge entry point and CUI packet handling on the `worldedit:cui` channel
- Persistent per-player edit history with paying undo, stored per world
- Survival-only protection for blocks an edit must never overwrite, with `@modid` and `#tag` support

### How a survival edit works
- An edit is buffered, costed, and paid for **before** a single block is written. Items come from the
  ME network reached through a wireless terminal (inventory or Curios), then from the inventory.
  If anything is missing the edit is cancelled and nothing is taken.
- Paid edits are applied a chunk at a time in the background so the server does not stall.
- A bought block is refunded the moment it leaves the world, not only on undo. Paste 100 planks,
  then paste 10 stone over part of them: the 10 planks that got covered go straight back to storage
  while the stone paste runs.
- A refund happens exactly once. Each bought block is tracked by position and state, so a refund
  cannot be repeated by covering a block, undoing the cover and undoing again. Blocks that never
  came from storage (containers, world blocks, blocks you placed by hand, free edits) are voided, and
  a block you mined or replaced yourself is not paid back.
- `//undo` restores the blocks an edit replaced and refunds what is still standing where the edit
  left it, so mining 4 of the 52 stone first refunds 48.
- `//undo nearest` undoes the applied edit closest to you in the current world.
- `//redo` re-applies an undone edit and charges for it again (the undo refunded it).
- History is stored per player on disk, so undo and redo still work after a reconnect or a restart.
- History and block claims are stored **per world**, under the save's folder name
  (`survival-history/<world>/<uuid>/` and `survival-claims/<world>/`). Opening a different save
  starts with an empty history, so an edit from one world can never be undone into another.
- Blocks on the protected list cannot be overwritten by a survival edit. The whole edit is refused
  before anything is paid for, and the message names the blocks that were in the way. Creative-mode
  edits are not restricted.

### Configuration
All settings live in `config/worldedit/worldedit.properties`. The file is written from the defaults
shipped with the mod, so it holds only the keys below (plus the two log keys) and keeps its comments;
any value you change is written back as you set it.

| Key | Default | Meaning |
| --- | --- | --- |
| `survival.blocks-per-tick` | `1024` | Blocks all edits together may write per tick. |
| `survival.per-chunk-delay-ms` | `0` | Wait before writing into the next chunk. |
| `survival.max-blocks-per-edit` | `250000` | Largest single edit, `0` for no limit. |
| `survival.history-limit` | `250` | Edits kept per player, `0` for no limit. |
| `survival.allow-schematics` | `false` | Allow `//schem` and clipboard save/load in survival. |
| `survival.drop-if-network-is-full` | `true` | Drop returned items that fit nowhere, instead of discarding them. |
| `survival.protected-blocks` | see below | Blocks a survival edit may not overwrite. |

`survival.protected-blocks` takes a comma list in three forms: `minecraft:bedrock` for one block,
`@ae2` for every block of a mod (`ae2:*` works too), and `#minecraft:logs` for a block tag. A
leading `!` is an exception that always wins, so `@ae2,!ae2:controller` protects all of AE2 but
leaves its controller editable. The
shipped list covers what cannot be mined back (bedrock, portals, spawners, command blocks), the
containers whose contents an edit never copies (chests, barrels, shulker boxes, ender chests), and
the expensive multiblock cores of the pack (AE2, Mekanism, AllTheModium). Add `@mekanism` there to
protect a whole mod at once.

AE2 and AE2WTLib are required dependencies: they are what an edit is paid from.

### Build and run
- Requires Java 21
- Run from the `worldedit-neoforge` module:
  - Linux / macOS: `cd worldedit-neoforge && ../gradlew :worldedit-neoforge:runClient`
  - Windows (Git Bash / cmd): `cd worldedit-neoforge && ..\gradlew :worldedit-neoforge:runClient`

Existing mods to test with can be dropped into `worldedit-neoforge/run/mods/`.

### Status
- Mod id: `worldedit`
- Mod name: `SurvivalEdit`
- Target platform: NeoForge on Minecraft `1.21.1`
- Mod version: `1.0`

### Repo
This repository is maintained as `M9MX/survivaledit` and is no longer synced with the upstream
EngineHub WorldEdit repository.

### Links
- [Issue tracker](https://github.com/M9MX/survivaledit/issues)
