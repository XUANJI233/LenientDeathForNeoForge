# LenientDeath（NeoForge 1.21.x，支持 1.21-1.21.11）

[English README](README.md)

这是 Fabric 模组 [JackFred2/LenientDeath](https://github.com/JackFred2/LenientDeath) 的 NeoForge 移植版本。
本项目在开发过程中大量使用了 AI 辅助编程与代码重构，包括但不限于代码生成、调试分析和文档撰写。

## 游戏内命令

所有命令要求 OP 权限（等级 >= 2）。除特殊标注外，`set` 命令修改后**立即生效**并**自动保存**到当前世界的 `serverconfig/lenientdeath-server.toml`。

### 设置配置值

`/lenientdeath config set <键> <值>`

以下为全部可设置的键名、值类型及说明：

| 键名 | 值类型 | 默认值 | 说明 |
|------|--------|--------|------|
| `enabled` | `true` \| `false` | `true` | 死亡保留物品总开关。关闭后所有保留规则均不生效。 |
| `byItemTypeEnabled` | `true` \| `false` | `true` | 按物品类型（护甲、工具、武器等）分类保留的总开关。 |
| `deathCoordinates` | `true` \| `false` | `true` | 重生后在聊天栏显示死亡坐标（含维度信息）。 |
| `itemGlow` | `true` \| `false` | `true` | 私有高亮：仅物品归属玩家能看到掉落物发光，其他玩家不可见。 |
| `itemResilience` | `true` \| `false` | `true` | 使死亡掉落物免疫火焰和爆炸伤害，减少意外销毁。 |
| `voidRecovery` | `true` \| `false` | `true` | 虚空恢复：掉落物落入虚空时自动传送到安全位置。 |
| `hazardRecovery` | `true` \| `false` | `true` | 火焰/岩浆恢复：掉落物着火或在岩浆中时自动传送到安全位置。 |
| `voidRecoveryDebug` | `true` \| `false` | `false` | 虚空恢复调试日志。**仅运行时有效，不保存到配置文件，重新加载世界后自动重置为 `false`。** |
| `voidRecoveryMode` | 枚举 | `DEATH_DROPS_ONLY` | 虚空/火焰恢复的作用范围（输入时有自动补全）：`DEATH_DROPS_ONLY` = 仅恢复死亡掉落物，`ALL_DROPS` = 恢复所有掉落物（含主动丢弃）。 |
| `restoreSlots` | `true` \| `false` | `true` | 保留物品还原到死亡前的原始背包槽位（工具栏、护甲栏等）。 |
| `privateHighlightScanIntervalTicks` | 整数 `1`-`200` | `10` | 私有高亮扫描间隔（单位：tick，20 tick = 1 秒）。 |
| `privateHighlightScanRadius` | 浮点数 `8.0`-`256.0` | `96.0` | 私有高亮扫描半径（单位：方块）。超出范围的掉落物不会高亮。 |
| `privateHighlightMaxScannedEntities` | 整数 `16`-`4096` | `256` | 每次扫描最多处理的掉落物实体数，用于限制服务器开销。 |
| `voidRecoveryWindowTicks` | 整数 `1`-`1200` | `10` | 恢复限流统计窗口长度（单位：tick）。 |
| `voidRecoveryMaxRecoveries` | 整数 `1`-`100` | `3` | 一个统计窗口内允许的最大恢复次数，超出后进入冷却。 |
| `voidRecoveryCooldownTicks` | 整数 `1`-`1200` | `10` | 达到恢复上限后的冷却时长（单位：tick）。 |

示例：

```
/lenientdeath config set enabled false
/lenientdeath config set voidRecoveryMode ALL_DROPS
/lenientdeath config set privateHighlightScanRadius 128.0
```

### 查看配置值

`/lenientdeath config get <键>`

支持与 `set` 相同的全部键名。数值类会同时显示允许范围，枚举类会显示所有可选值。

示例：

```
/lenientdeath config get voidRecoveryMode
/lenientdeath config get privateHighlightScanRadius
```

### 重载配置文件

`/lenientdeath config reload`

从当前世界的 `serverconfig/lenientdeath-server.toml` 重新读取配置并立即应用。适用于手动编辑配置文件后刷新生效，无需重启。

### 始终保留列表

管理"始终保留"的物品 ID 和物品标签。列表中的物品在玩家死亡时一定会被保留，不受其他规则影响。

| 命令 | 说明 |
|------|------|
| `/lenientdeath config preserve item add <物品ID>` | 添加物品 ID（示例：`minecraft:totem_of_undying`） |
| `/lenientdeath config preserve item remove <物品ID>` | 移除物品 ID |
| `/lenientdeath config preserve item list` | 查看当前物品 ID 列表 |
| `/lenientdeath config preserve tag add <标签ID>` | 添加物品标签（示例：`minecraft:logs`） |
| `/lenientdeath config preserve tag remove <标签ID>` | 移除物品标签 |
| `/lenientdeath config preserve tag list` | 查看当前物品标签列表 |

### 始终掉落列表

管理"始终掉落"的物品 ID 和物品标签。列表中的物品在玩家死亡时一定会掉落，优先级高于保留规则。

| 命令 | 说明 |
|------|------|
| `/lenientdeath config drop item add <物品ID>` | 添加物品 ID |
| `/lenientdeath config drop item remove <物品ID>` | 移除物品 ID |
| `/lenientdeath config drop item list` | 查看当前物品 ID 列表 |
| `/lenientdeath config drop tag add <标签ID>` | 添加物品标签 |
| `/lenientdeath config drop tag remove <标签ID>` | 移除物品标签 |
| `/lenientdeath config drop tag list` | 查看当前物品标签列表 |

### 调试

`/lenientdeath debug status`

输出运行时调试信息，包括：反射访问器状态、私有高亮跟踪玩家数、调试日志开关状态、已缓存的保留物品和背包快照数量、待发送死亡坐标数量。用于排查模组运行异常。

## 如何修改配置

运行时配置文件路径（每个世界独立）：

- `<世界存档>/serverconfig/lenientdeath-server.toml`

建议在游戏或服务器停止时修改该文件，保存后重启以确保全部配置生效。也可以在运行中手动修改后使用 `/lenientdeath config reload` 命令重载。

### 快速操作步骤

1. 若配置文件不存在，先运行一次客户端或服务端以自动生成默认配置。
2. 打开 `<世界存档>/serverconfig/lenientdeath-server.toml`。
3. 按需修改 `[General]`、`[Randomizer]`、`[NBT]`、`[Features]`、`[Lists]`、`[ItemTypes]` 下的配置项。
4. 保存并重启游戏/服务器，或在游戏中执行 `/lenientdeath config reload`。

### 旧版配置迁移

若检测到旧版全局配置文件 `config/lenientdeath-common.toml`，首次进入世界时会自动将其内容迁移到该世界的 `serverconfig/lenientdeath-server.toml` 并立即生效。

## 配置文件完整示例

```toml
[General]
# 死亡保留物品总开关，关闭后所有保留规则均不生效
enabled = true

[Randomizer]
# 对未被其他规则命中的物品启用随机保留
enabled = false
# 基础随机保留概率（百分比，0-100）
chancePercent = 25
# 幸运值加算因子（百分比），如 20 表示每点幸运额外加 20%
luckAdditive = 20
# 幸运值乘算因子，公式：chance * (1 + multiplier * luck) + additive * luck
# 示例：luck=2, multiplier=1.0, additive=20%, chance=25% -> 25%*(1+2)+40%=115% -> 截取为 100%
luckMultiplier = 1.0

[NBT]
# 根据 NBT 布尔标记保留物品（类似灵魂绑定）
# 示例：/give @s diamond{Soulbound:1b} -> 该钻石始终保留
enabled = false
# 检查的 NBT 布尔键名
nbtKey = "Soulbound"

[Features]
# 重生后在聊天栏显示死亡坐标（含维度信息）
deathCoordinates = true
# 启用私有高亮：仅物品归属的玩家能看到掉落物发光
itemGlow = true
# 私有高亮扫描间隔（tick，范围 1-200），10 = 每 0.5 秒扫描一次
privateHighlightScanIntervalTicks = 10
# 私有高亮扫描半径（方块，范围 8-256）
privateHighlightScanRadius = 96.0
# 每次扫描最多处理的掉落物实体数（范围 16-4096）
privateHighlightMaxScannedEntities = 256
# 让死亡掉落物免疫火焰和爆炸伤害
itemResilience = true
# 虚空恢复：当掉落物落入虚空时传送到安全位置
voidRecovery = true
# 火焰/岩浆恢复：当掉落物着火或在岩浆中时传送到安全位置
hazardRecovery = true
# 虚空/火焰恢复范围：DEATH_DROPS_ONLY（仅死亡掉落物）或 ALL_DROPS（所有掉落物，含主动丢弃）
voidRecoveryMode = "DEATH_DROPS_ONLY"
# 尝试将保留的物品放回死亡前的原始槽位
restoreSlots = true

# --- 虚空恢复限流 ---
# 恢复次数统计窗口（tick，范围 1-1200），10 = 0.5 秒
voidRecoveryWindowTicks = 10
# 一个窗口内最多恢复次数（范围 1-100）
voidRecoveryMaxRecoveries = 3
# 达到上限后的冷却时长（tick，范围 1-1200）
voidRecoveryCooldownTicks = 10

# 注意：voidRecoveryDebug 为运行时标志，不保存在配置文件中，
# 每次加载世界后自动重置为 false，需通过命令 /lenientdeath config set voidRecoveryDebug true 开启

[Lists]
# 始终保留的物品 ID（示例：minecraft:totem_of_undying）
alwaysPreservedItems = ["minecraft:totem_of_undying"]
# 始终保留的物品标签（示例：minecraft:logs）
alwaysPreservedTags = ["minecraft:logs"]
# 始终掉落的物品 ID
alwaysDroppedItems = ["minecraft:diamond"]
# 始终掉落的物品标签
alwaysDroppedTags = ["minecraft:planks"]

[ItemTypes]
# 启用按物品类型分类处理，每个类型可设为 PRESERVE / DROP / IGNORE
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

## 保留规则说明

物品在死亡时按以下优先级判定保留或掉落：

1. **NBT 标记**：物品 CustomData 中包含指定布尔键（默认 `Soulbound:1b`）时始终保留。需在配置中开启 `[NBT].enabled`。
2. **手动列表**：`alwaysPreservedItems`/`alwaysPreservedTags` 中的物品始终保留；`alwaysDroppedItems`/`alwaysDroppedTags` 中的物品始终掉落（优先级高于保留）。
3. **物品类型规则**：按护甲、工具、武器、食物、药水、Curios 等分类，每类可配置 `PRESERVE`（保留）、`DROP`（掉落）或 `IGNORE`（不做特殊处理）。
4. **随机保留**：未被上述规则命中的物品，按基础概率 + 幸运修正随机判定保留数量。需在配置中开启 `[Randomizer].enabled`。

### 恢复机制

- **虚空恢复**（`voidRecovery`）：掉落物低于世界最低高度时自动传送到安全位置。
- **火焰/岩浆恢复**（`hazardRecovery`）：掉落物着火或在岩浆中时自动传送到安全位置。
- 安全位置选取顺序：玩家历史安全点 -> 三维距离最近的有效落点 -> 出生点附近。
- `voidRecoveryMode` 控制作用范围：`DEATH_DROPS_ONLY`（默认，仅死亡掉落）或 `ALL_DROPS`（所有掉落物）。
- 恢复限流：通过 `voidRecoveryWindowTicks`、`voidRecoveryMaxRecoveries`、`voidRecoveryCooldownTicks` 三个参数防止短时间内反复恢复。

### 其他功能

- **私有高亮**（`itemGlow`）：死亡掉落物仅对归属玩家显示发光效果，其他玩家不可见。
- **掉落物韧性**（`itemResilience`）：死亡掉落物免疫火焰和爆炸伤害。
- **死亡坐标**（`deathCoordinates`）：重生后在聊天栏显示死亡位置及维度。
- **原槽位还原**（`restoreSlots`）：保留的物品自动放回死亡前所在的背包槽位。

## 开发命令

- 刷新依赖：`gradlew --refresh-dependencies`
- 清理构建：`gradlew clean`
- 编译：`gradlew compileJava`
- 测试：`gradlew test`