# LenientDeath (NeoForge 1.21.1)

[中文文档 / README_CN](README_CN.md)

NeoForge port of Fabric mod [JackFred2/LenientDeath](https://github.com/JackFred2/LenientDeath).

## How To Modify Configuration

The runtime config file is:

- `run/config/lenientdeath-common.toml`

Edit this file while the game/server is stopped, then restart to apply all changes reliably.

### Quick Steps

1. Run once to generate config files (if missing): `gradlew runClient` or your server run config.
2. Open `run/config/lenientdeath-common.toml`.
3. Change values under sections such as `[Features]`, `[ItemTypes]`, `[Lists]`.
4. Save and restart game/server.

## In-Game Commands (Real-Time Apply)

You can modify key config values at runtime (OP level 2+):

- `/lenientdeath config set itemGlow true`
- `/lenientdeath config set privateHighlightScanIntervalTicks 10`
- `/lenientdeath config set privateHighlightScanRadius 96`
- `/lenientdeath config set privateHighlightMaxScannedEntities 256`
- `/lenientdeath config set voidRecovery true`
- `/lenientdeath config set voidRecoveryWindowTicks 10`
- `/lenientdeath config set voidRecoveryMaxRecoveries 3`
- `/lenientdeath config set voidRecoveryCooldownTicks 10`
- `/lenientdeath config set restoreSlots true`

These commands apply immediately and auto-save to `lenientdeath-common.toml`.

Additional helpers:

- `/lenientdeath config reload` (reload from `lenientdeath-common.toml` and apply immediately)
- `/lenientdeath config get <key>` (show current value; numeric keys include allowed range)
- `/lenientdeath config preserve item add <item_id>` (add always-preserved item ID, e.g. `minecraft:totem_of_undying`)
- `/lenientdeath config preserve item remove <item_id>` (remove always-preserved item ID)
- `/lenientdeath config preserve item list` (list always-preserved item IDs)
- `/lenientdeath config preserve tag add <tag_id>` (add always-preserved tag, e.g. `minecraft:logs`)
- `/lenientdeath config preserve tag remove <tag_id>` (remove always-preserved tag)
- `/lenientdeath config preserve tag list` (list always-preserved tags)
- `/lenientdeath config drop item add <item_id>` (add always-dropped item ID)
- `/lenientdeath config drop item remove <item_id>` (remove always-dropped item ID)
- `/lenientdeath config drop item list` (list always-dropped item IDs)
- `/lenientdeath config drop tag add <tag_id>` (add always-dropped tag)
- `/lenientdeath config drop tag remove <tag_id>` (remove always-dropped tag)
- `/lenientdeath config drop tag list` (list always-dropped tags)
- `/lenientdeath debug status` (print runtime debug counters and compatibility status)

## Configuration Example

```toml
[Features]
# Send death coordinates to the player chat after respawn/clone
deathCoordinates = true
# Enable owner-only private glow for tracked dropped items
itemGlow = true
# Private highlight scan interval in ticks (1..200)
privateHighlightScanIntervalTicks = 10
# Private highlight scan radius in blocks (8..256)
privateHighlightScanRadius = 96.0
# Max number of item entities processed per scan (16..4096)
privateHighlightMaxScannedEntities = 256
# Keep fragile item entities from being destroyed too quickly
itemResilience = true
# Recover dropped items from void into safe positions
voidRecovery = true
# Try to restore preserved items back to their original inventory slots
restoreSlots = true

# --- Void Recovery Limiter ---
# Time window for counting recoveries (ticks, 1..1200)
voidRecoveryWindowTicks = 10
# Maximum recoveries allowed inside one window (1..100)
voidRecoveryMaxRecoveries = 3
# Cooldown after hitting limit (ticks, 1..1200)
voidRecoveryCooldownTicks = 10

[Lists]
# Always preserve these exact item IDs
alwaysPreservedItems = ["minecraft:totem_of_undying"]
# Always preserve items matching these tags
alwaysPreservedTags = ["minecraft:logs"]
# Always drop these exact item IDs
alwaysDroppedItems = ["minecraft:diamond"]
# Always drop items matching these tags
alwaysDroppedTags = ["minecraft:planks"]
```

## Rule Highlights

- Preserve by item type: armor, tools, weapons, food, potions, curios.
- Preserve by specific item ID list: `alwaysPreservedItems`.
- Preserve by tag list: `alwaysPreservedTags`.
- `itemGlow` controls private owner-only highlight.
- `voidRecovery` controls safe-position recovery for dropped items in void.

## Development

- Refresh dependencies: `gradlew --refresh-dependencies`
- Clean build: `gradlew clean`
- Compile: `gradlew compileJava`
- Test: `gradlew test`
