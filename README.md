# SurvivalEdit

**A survival-focused WorldEdit fork for NeoForge.**

SurvivalEdit brings WorldEdit-style building tools to survival modpacks. Edits use resources from
an Applied Energistics 2 network or the player's inventory, are placed gradually, and can be
undone through persistent per-world history.

> **Important:** SurvivalEdit uses the `worldedit` mod ID for WorldEditCUI compatibility. Do not
> install it together with the official WorldEdit mod.

## Features

- WorldEdit selection and editing commands on NeoForge.
- The `worldedit:axe` selection item, recipe, model, and texture.
- Resource payment through an AE2 network reached by wireless terminal, then the player's inventory.
- Gradual, chunk-based application of paid edits.
- Persistent per-player history stored on disk.
- Paid undo and redo:
  - `//undo` restores an edit and refunds resources that are still present.
  - `//undo nearest` undoes the nearest applied edit in the current world.
  - `//redo` applies an undone edit and charges for it again.
- Protection for blocks that should not be overwritten in survival.
- Protection rules by block, mod namespace, or block tag.
- Optional survival schematic support.
- WorldEditCUI compatibility through the `worldedit` channel.

## Requirements

| Requirement | Supported value |
| --- | --- |
| Minecraft | `1.21.1` |
| Loader | NeoForge |
| Java | 21 |
| Required mods | Applied Energistics 2 and AE2WTLib |

SurvivalEdit is NeoForge-only. It is not a Bukkit, Paper, Fabric, or Sponge plugin.

## Installation

1. Install NeoForge for Minecraft `1.21.1`.
2. Install Applied Energistics 2 and AE2WTLib.
3. Download the `survivaledit-neoforge-...-dist.jar` release file.
4. Put the jar in the server's `mods` directory.
5. Start the server once, then configure `config/worldedit/worldedit.properties`.

Do not install the official WorldEdit mod alongside SurvivalEdit.

## How survival edits work

Before a paid edit starts, SurvivalEdit checks its cost and protected blocks. If the edit cannot be
paid for or would overwrite a protected block, it is refused before resources are taken.

Paid blocks are tracked by position and state. When a purchased block is replaced, the resource is
refunded once if it is still identifiable as a purchased block. Blocks that were not purchased
through SurvivalEdit are not refunded.

History and claim data are stored per world. A history from one save cannot be applied to another
save.

## WorldEdit differences

| Area | WorldEdit | SurvivalEdit |
| --- | --- | --- |
| Platform | Multiple platform builds | NeoForge only |
| Edit cost | Normally free | Paid from AE2 or inventory in survival |
| Placement | Normal WorldEdit placement | Gradual chunk-based placement for paid edits |
| History | Standard WorldEdit history | Persistent per-player, per-world history |
| Undo | Standard WorldEdit behavior | Restores blocks and refunds eligible purchased blocks |
| Redo | Standard WorldEdit behavior | Charges resources again |
| Protection | WorldEdit/server permissions and configuration | Additional survival protected-block rules |
| Schematics | Standard WorldEdit behavior | Survival schematic use is configurable and disabled by default |
| Mod identity | `worldedit` | `worldedit`, intentionally retained for WorldEditCUI compatibility |

SurvivalEdit is not a drop-in replacement for a WorldEdit plugin and is not compatible with an
official WorldEdit installation in the same instance.

## Configuration

Configuration is stored in `config/worldedit/worldedit.properties`.

| Key | Default | Description |
| --- | ---: | --- |
| `survival.blocks-per-tick` | `1024` | Maximum blocks all edits may write per tick. |
| `survival.per-chunk-delay-ms` | `0` | Delay before writing to the next chunk. |
| `survival.max-blocks-per-edit` | `250000` | Maximum size of one edit; `0` disables the limit. |
| `survival.history-limit` | `250` | Number of edits kept per player; `0` disables the limit. |
| `survival.allow-schematics` | `false` | Allow schematic and clipboard use in survival. |
| `survival.drop-if-network-is-full` | `true` | Drop returned items when storage cannot accept them. |
| `survival.protected-blocks` | See below | Blocks survival edits may not overwrite. |

`survival.protected-blocks` accepts:

- `minecraft:bedrock` — one block.
- `@ae2` or `ae2:*` — all blocks from a mod namespace.
- `#minecraft:logs` — every block in a tag.
- Prefix any rule with `!` to make an exception, such as `@ae2,!ae2:controller`.

The shipped protection list covers blocks that cannot safely be mined back, containers whose
contents are not copied by an edit, and selected expensive multiblock components. Review it before
using SurvivalEdit in a modpack.

## Known limitations

- NeoForge and Minecraft `1.21.1` are the supported platform and version.
- The official WorldEdit mod cannot be installed at the same time.
- Survival payment requires AE2/AE2WTLib integration.
- Paid edits are intentionally gradual rather than instant.
- Schematic support in survival is disabled by default.
- Behavior involving modded block entities should be tested in the target modpack before enabling
  broad editing permissions.

## Project status

SurvivalEdit is a focused fork rather than an upstream WorldEdit distribution. The project keeps
WorldEdit's core functionality where it is useful and adds survival payment, history, protection,
and NeoForge integration.

The current WorldEdit base is `7.3.8`. SurvivalEdit has its own version and release notes.

## Building

Building requires Java 21. Use the Gradle wrapper:

```text
./gradlew :worldedit-neoforge:build
```

The distributable jar is produced in:

```text
worldedit-neoforge/build/libs/
```

Use the jar with the `-dist` classifier for installation.

## Support and contributions

- Report bugs: [GitHub Issues](https://github.com/M9MX/survivaledit/issues)
- Read the project-specific changes in [CHANGELOG.md](CHANGELOG.md).
- `CHANGELOG.txt` is retained as the upstream WorldEdit history and is not the SurvivalEdit
  release changelog.

## License and attribution

SurvivalEdit is derived from WorldEdit and remains subject to the applicable GNU General Public
License terms. See [LICENSE.txt](LICENSE.txt) for the license text and retain the upstream
copyright and attribution notices.
