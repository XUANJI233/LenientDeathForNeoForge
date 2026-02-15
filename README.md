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
- `/lenientdeath debug status` (print runtime debug counters and compatibility status)

## Configuration Example

```toml
[Features]
deathCoordinates = true
itemGlow = true
privateHighlightScanIntervalTicks = 10
privateHighlightScanRadius = 96.0
privateHighlightMaxScannedEntities = 256
itemResilience = true
voidRecovery = true
restoreSlots = true

# --- Void Recovery Limiter ---
voidRecoveryWindowTicks = 10
voidRecoveryMaxRecoveries = 3
voidRecoveryCooldownTicks = 10
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
