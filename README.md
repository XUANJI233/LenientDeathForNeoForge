# LenientDeath (NeoForge 1.21.x, 1.21-1.21.11)

[中文文档 / README_CN](README_CN.md)

NeoForge port of Fabric mod [JackFred2/LenientDeath](https://github.com/JackFred2/LenientDeath).
This project was developed with extensive use of AI-assisted programming and code refactoring, including but not limited to code generation, debugging analysis and documentation writing.

## In-Game Commands

All commands require OP permission (level >= 2). Unless otherwise noted, `set` commands take effect **immediately** and are **auto-saved** to the current world's `serverconfig/lenientdeath-server.toml`.

### Set Config Values

`/lenientdeath config set <key> <value>`

All available keys, value types and descriptions:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | `true` \| `false` | `true` | Master switch for preserving items on death. When off, all preservation rules are disabled. |
| `byItemTypeEnabled` | `true` \| `false` | `true` | Master switch for item-type-based preservation (armor, tools, weapons, etc.). |
| `deathCoordinates` | `true` \| `false` | `true` | Show death coordinates (with dimension) in chat upon respawn. |
| `itemGlow` | `true` \| `false` | `true` | Private highlight: only the item owner can see dropped items glow. |
| `itemResilience` | `true` \| `false` | `true` | Make death-dropped items immune to fire and explosion damage. |
| `voidRecovery` | `true` \| `false` | `true` | Void recovery: auto-teleport dropped items to a safe position when falling into the void. |
| `hazardRecovery` | `true` \| `false` | `true` | Hazard recovery: auto-teleport dropped items to a safe position when on fire or in lava. |
| `voidRecoveryDebug` | `true` \| `false` | `false` | Debug logging for void/hazard recovery. **Runtime-only; not saved to config; resets to `false` on world reload.** |
| `voidRecoveryMode` | enum | `DEATH_DROPS_ONLY` | Scope for void/hazard recovery (tab-completable): `DEATH_DROPS_ONLY` = only death drops, `ALL_DROPS` = all drops including manually discarded items. |
| `restoreSlots` | `true` \| `false` | `true` | Restore preserved items to their original inventory slots (hotbar, armor, etc.). |
| `privateHighlightScanIntervalTicks` | int `1`-`200` | `10` | Private highlight scan interval (ticks; 20 ticks = 1 second). |
| `privateHighlightScanRadius` | float `8.0`-`256.0` | `96.0` | Private highlight scan radius (blocks). Items beyond this are not highlighted. |
| `privateHighlightMaxScannedEntities` | int `16`-`4096` | `256` | Max item entities processed per scan; limits server overhead. |
| `voidRecoveryWindowTicks` | int `1`-`1200` | `10` | Recovery rate-limit window length (ticks). |
| `voidRecoveryMaxRecoveries` | int `1`-`100` | `3` | Max recoveries allowed within one window before cooldown. |
| `voidRecoveryCooldownTicks` | int `1`-`1200` | `10` | Cooldown duration after hitting the recovery limit (ticks). |

Examples:

```
/lenientdeath config set enabled false
/lenientdeath config set voidRecoveryMode ALL_DROPS
/lenientdeath config set privateHighlightScanRadius 128.0
```

### Get Config Values

`/lenientdeath config get <key>`

Supports all keys listed above. Numeric keys display their allowed range; enum keys display all valid options.

Examples:

```
/lenientdeath config get voidRecoveryMode
/lenientdeath config get privateHighlightScanRadius
```

### Reload Config File

`/lenientdeath config reload`

Re-reads `serverconfig/lenientdeath-server.toml` from the current world and applies changes immediately. Useful after manual file edits without restarting.

### Always-Preserve List

Manage always-preserved item IDs and tags. Items on this list are always kept on death, regardless of other rules.

| Command | Description |
|---------|-------------|
| `/lenientdeath config preserve item add <item_id>` | Add item ID (e.g. `minecraft:totem_of_undying`) |
| `/lenientdeath config preserve item remove <item_id>` | Remove item ID |
| `/lenientdeath config preserve item list` | Show current item ID list |
| `/lenientdeath config preserve tag add <tag_id>` | Add item tag (e.g. `minecraft:logs`) |
| `/lenientdeath config preserve tag remove <tag_id>` | Remove item tag |
| `/lenientdeath config preserve tag list` | Show current tag list |

### Always-Drop List

Manage always-dropped item IDs and tags. Items on this list are always dropped on death; this overrides preservation rules.

| Command | Description |
|---------|-------------|
| `/lenientdeath config drop item add <item_id>` | Add item ID |
| `/lenientdeath config drop item remove <item_id>` | Remove item ID |
| `/lenientdeath config drop item list` | Show current item ID list |
| `/lenientdeath config drop tag add <tag_id>` | Add item tag |
| `/lenientdeath config drop tag remove <tag_id>` | Remove item tag |
| `/lenientdeath config drop tag list` | Show current tag list |

### Debug

`/lenientdeath debug status`

Prints runtime debug info including: reflection accessor status, number of players tracked by private highlight, debug logging toggle state, cached preserved-items and inventory snapshot counts, and pending death coordinate messages. Useful for troubleshooting mod issues.

## How To Modify Configuration

Runtime config file path (per-world):

- `<world>/serverconfig/lenientdeath-server.toml`

It is recommended to edit this file while the game/server is stopped, then restart to ensure all changes take effect. Alternatively, edit while running and use `/lenientdeath config reload` to apply.

### Quick Steps

1. If no config file exists, run the client or server once to auto-generate defaults.
2. Open `<world>/serverconfig/lenientdeath-server.toml`.
3. Modify values under `[General]`, `[Randomizer]`, `[NBT]`, `[Features]`, `[Lists]`, `[ItemTypes]` as needed.
4. Save and restart, or run `/lenientdeath config reload` in-game.

### Legacy Config Migration

If a legacy global config `config/lenientdeath-common.toml` is detected, its contents are automatically migrated to the current world's `serverconfig/lenientdeath-server.toml` on first world load and applied immediately.

## Full Configuration Example

```toml
[General]
# Master switch for preserving items on death
enabled = true

[Randomizer]
# Random preservation for items not matched by other rules
enabled = false
# Base random keep chance (percent, 0-100)
chancePercent = 25
# Luck additive factor (percent): each luck point adds this to chance
luckAdditive = 20
# Luck multiplier factor: formula = chance * (1 + multiplier * luck) + additive * luck
# Example: luck=2, multiplier=1.0, additive=20%, chance=25% -> 25%*(1+2)+40%=115% -> clamped to 100%
luckMultiplier = 1.0

[NBT]
# Preserve items with a specific NBT boolean tag (soulbound-like)
# Example: /give @s diamond{Soulbound:1b} -> always preserved
enabled = false
nbtKey = "Soulbound"

[Features]
# Show death coordinates in chat after respawn (with dimension)
deathCoordinates = true
# Private owner-only glow highlight for dropped items
itemGlow = true
# Private highlight scan interval in ticks (1-200), 10 = every 0.5s
privateHighlightScanIntervalTicks = 10
# Scan radius in blocks (8.0-256.0)
privateHighlightScanRadius = 96.0
# Max item entities processed per scan (16-4096)
privateHighlightMaxScannedEntities = 256
# Make death-dropped items immune to fire/explosion
itemResilience = true
# Recover items from void to safe position
voidRecovery = true
# Recover items from fire/lava to safe position
hazardRecovery = true
# Void/hazard recovery scope: DEATH_DROPS_ONLY (default) or ALL_DROPS
voidRecoveryMode = "DEATH_DROPS_ONLY"
# Restore preserved items to their original inventory slots
restoreSlots = true

# --- Void Recovery Rate Limiter ---
# Time window for counting recoveries (ticks, 1-1200)
voidRecoveryWindowTicks = 10
# Max recoveries inside one window (1-100)
voidRecoveryMaxRecoveries = 3
# Cooldown after hitting limit (ticks, 1-1200)
voidRecoveryCooldownTicks = 10

# Note: voidRecoveryDebug is a runtime-only flag (not saved in config).
# It resets to false on each world load. Enable via: /lenientdeath config set voidRecoveryDebug true

[Lists]
# Always preserve these exact item IDs
alwaysPreservedItems = ["minecraft:totem_of_undying"]
# Always preserve items matching these tags
alwaysPreservedTags = ["minecraft:logs"]
# Always drop these exact item IDs
alwaysDroppedItems = ["minecraft:diamond"]
# Always drop items matching these tags
alwaysDroppedTags = ["minecraft:planks"]

[ItemTypes]
# Enable item type rules (PRESERVE / DROP / IGNORE per type)
enabled = true
helmets = "PRESERVE"
chestplates = "PRESERVE"
leggings = "PRESERVE"
boots = "PRESERVE"
elytras = "PRESERVE"
shields = "PRESERVE"
tools = "PRESERVE"
weapons = "PRESERVE"
meleeWeapons = "IGNORE"
rangedWeapons = "IGNORE"
utilityTools = "IGNORE"
fishingRods = "IGNORE"
buckets = "IGNORE"
enchantedBooks = "IGNORE"
totems = "IGNORE"
blockItems = "IGNORE"
spawnEggs = "IGNORE"
arrows = "IGNORE"
food = "PRESERVE"
potions = "PRESERVE"
curios = "PRESERVE"
```

## Preservation Rules

Items are evaluated on death in the following priority order:

1. **NBT Tag**: Items with the specified boolean key in CustomData (default `Soulbound:1b`) are always preserved. Requires `[NBT].enabled = true`.
2. **Manual Lists**: Items in `alwaysPreservedItems`/`alwaysPreservedTags` are always preserved; items in `alwaysDroppedItems`/`alwaysDroppedTags` are always dropped (drop takes priority over preserve).
3. **Item Type Rules**: Categorized by armor, tools, weapons, food, potions, Curios, etc. Each type can be set to `PRESERVE`, `DROP`, or `IGNORE`.
4. **Random Preservation**: Items not matched by any rule above are randomly kept based on base chance + luck modifier. Requires `[Randomizer].enabled = true`.

### Recovery Mechanics

- **Void Recovery** (`voidRecovery`): When dropped items fall below the world's minimum height, they are teleported to a safe position.
- **Hazard Recovery** (`hazardRecovery`): When dropped items are on fire or in lava, they are teleported to a safe position.
- Safe position selection order: player historical safe point -> nearest valid 3D landing spot -> near world spawn.
- `voidRecoveryMode` controls scope: `DEATH_DROPS_ONLY` (default, death drops only) or `ALL_DROPS` (all drops).
- Rate limiting: `voidRecoveryWindowTicks`, `voidRecoveryMaxRecoveries`, and `voidRecoveryCooldownTicks` prevent excessive recoveries in a short period.

### Other Features

- **Private Highlight** (`itemGlow`): Death-dropped items glow only for the owner; invisible to other players.
- **Item Resilience** (`itemResilience`): Death-dropped items are immune to fire and explosion damage.
- **Death Coordinates** (`deathCoordinates`): Death location and dimension shown in chat upon respawn.
- **Slot Restoration** (`restoreSlots`): Preserved items are automatically placed back in their original inventory slots.

## Development

- Refresh dependencies: `gradlew --refresh-dependencies`
- Clean build: `gradlew clean`
- Compile: `gradlew compileJava`
- Test: `gradlew test`