# LenientDeath（NeoForge 1.21.x，支持 1.21-1.21.11）

[English README](README.md)

这是 Fabric 模组 [JackFred2/LenientDeath](https://github.com/JackFred2/LenientDeath) 的 NeoForge 移植版本。
使用大量AI进行了重构。




## 游戏内命令修改（实时生效）

拥有 OP（权限等级 2 及以上）时，可在游戏中直接修改关键配置：

- `/lenientdeath config set itemGlow true`：开启/关闭私有高亮（布尔值：`true|false`）。
- `/lenientdeath config set privateHighlightScanIntervalTicks 10`：设置私有高亮扫描间隔（范围：`1..200`）。
- `/lenientdeath config set privateHighlightScanRadius 96`：设置私有高亮扫描半径（范围：`8..256`）。
- `/lenientdeath config set privateHighlightMaxScannedEntities 256`：设置单次扫描最大处理掉落物数量（范围：`16..4096`）。
- `/lenientdeath config set voidRecovery true`：开启/关闭虚空恢复（布尔值：`true|false`）。
- `/lenientdeath config set voidRecoveryDebug true`：开启/关闭虚空恢复调试日志（布尔值：`true|false`，默认关闭）。
- `/lenientdeath config set voidRecoveryMode DEATH_DROPS_ONLY`：设置虚空恢复范围（`DEATH_DROPS_ONLY|ALL_DROPS`，默认 `DEATH_DROPS_ONLY`）。
- `/lenientdeath config set voidRecoveryWindowTicks 10`：设置虚空恢复统计窗口（范围：`1..1200`）。
- `/lenientdeath config set voidRecoveryMaxRecoveries 3`：设置窗口内最大恢复次数（范围：`1..100`）。
- `/lenientdeath config set voidRecoveryCooldownTicks 10`：设置达到上限后的冷却时长（范围：`1..1200`）。
- `/lenientdeath config set restoreSlots true`：开启/关闭原槽位恢复（布尔值：`true|false`）。

上述命令会立即生效，并自动保存到 `lenientdeath-common.toml`。

附加命令：

- `/lenientdeath config reload`：从 `lenientdeath-common.toml` 重新加载配置并立即应用（用于手动改文件后生效）。
- `/lenientdeath config get <key>`：查看当前配置值（数值类会同时显示允许范围）。
- `/lenientdeath config preserve item add <物品ID>`：添加“总是保留”物品ID（示例：`minecraft:totem_of_undying`）。
- `/lenientdeath config preserve item remove <物品ID>`：删除“总是保留”物品ID。
- `/lenientdeath config preserve item list`：查看当前“总是保留”物品ID列表。
- `/lenientdeath config preserve tag add <标签ID>`：添加“总是保留”标签（示例：`minecraft:logs`）。
- `/lenientdeath config preserve tag remove <标签ID>`：删除“总是保留”标签。
- `/lenientdeath config preserve tag list`：查看当前“总是保留”标签列表。
- `/lenientdeath config drop item add <物品ID>`：添加“总是掉落”物品ID。
- `/lenientdeath config drop item remove <物品ID>`：删除“总是掉落”物品ID。
- `/lenientdeath config drop item list`：查看当前“总是掉落”物品ID列表。
- `/lenientdeath config drop tag add <标签ID>`：添加“总是掉落”标签。
- `/lenientdeath config drop tag remove <标签ID>`：删除“总是掉落”标签。
- `/lenientdeath config drop tag list`：查看当前“总是掉落”标签列表。
- `/lenientdeath debug status`：输出调试状态（高亮跟踪数量、缓存数量、关键反射状态）用于排查问题。


## 如何修改配置

运行时配置文件路径：

- `run/config/lenientdeath-common.toml`

建议在游戏或服务器停止时修改该文件，保存后重启以确保全部配置生效。

### 快速操作步骤

1. 若配置文件不存在，先运行一次客户端或服务端以生成配置。
2. 打开 `run/config/lenientdeath-common.toml`。
3. 按需修改 `[Features]`、`[ItemTypes]`、`[Lists]` 下的配置项。
4. 保存并重启游戏/服务器。

## 配置示例

```toml
[Features]
# 玩家重生/克隆后在聊天栏显示死亡坐标
deathCoordinates = true
# 启用“仅归属玩家可见”的私有高亮
itemGlow = true
# 私有高亮扫描间隔（tick，范围 1..200）
privateHighlightScanIntervalTicks = 10
# 私有高亮扫描半径（方块，范围 8..256）
privateHighlightScanRadius = 96.0
# 单次扫描最多处理的掉落实体数量（范围 16..4096）
privateHighlightMaxScannedEntities = 256
# 提高掉落物生存韧性，减少意外销毁
itemResilience = true
# 启用虚空恢复（将掉落物挪到安全位置）
voidRecovery = true
# 启用虚空恢复调试日志（开发排查用，默认关闭）
voidRecoveryDebug = false
# 虚空恢复范围：仅死亡掉落（默认）或全部掉落
voidRecoveryMode = "DEATH_DROPS_ONLY"
# 尝试把保留物品放回死亡前的原槽位
restoreSlots = true

# --- 虚空恢复限流 ---
# 恢复次数统计窗口（tick，范围 1..1200）
voidRecoveryWindowTicks = 10
# 一个窗口内最多恢复次数（范围 1..100）
voidRecoveryMaxRecoveries = 3
# 达到上限后的冷却时长（tick，范围 1..1200）
voidRecoveryCooldownTicks = 10

[Lists]
# 这些物品ID始终保留
alwaysPreservedItems = ["minecraft:totem_of_undying"]
# 匹配这些标签的物品始终保留
alwaysPreservedTags = ["minecraft:logs"]
# 这些物品ID始终掉落
alwaysDroppedItems = ["minecraft:diamond"]
# 匹配这些标签的物品始终掉落
alwaysDroppedTags = ["minecraft:planks"]
```

## 规则说明

- 支持按物品类型保留：护甲、工具、武器、食物、药水、Curios。
- 支持按具体物品 ID 保留：`alwaysPreservedItems`。
- 支持按标签保留：`alwaysPreservedTags`。
- 支持按具体物品 ID 强制掉落：`alwaysDroppedItems`。
- 支持按标签强制掉落：`alwaysDroppedTags`。
- `itemGlow` 控制是否启用“仅归属玩家可见”的私有高亮。
- `voidRecovery` 控制是否启用掉落物虚空安全位置恢复。
- `voidRecoveryMode` 支持 `DEATH_DROPS_ONLY`（默认，仅死亡掉落恢复）和 `ALL_DROPS`（全部掉落都恢复）。
- 虚空恢复时会优先落到可站立表面；若附近无有效地面，会回退到世界出生点附近安全位置。

## 开发命令

- 刷新依赖：`gradlew --refresh-dependencies`
- 清理构建：`gradlew clean`
- 编译：`gradlew compileJava`
- 测试：`gradlew test`
