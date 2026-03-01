package com.lenientdeath.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * 模组配置定义。配置注册为 {@code ModConfig.Type.SERVER}，每个世界独立。
 * <p>
 * 配置文件位于 {@code <world>/serverconfig/lenientdeath-server.toml}。
 */
public class Config {
    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

    /**
     * 配置值定义容器。
     * <p>
     * 注意：类名保留为 {@code Common} 以保持历史兼容性（字段名 {@code Config.COMMON}
     * 在整个代码库中广泛引用），实际注册类型为 SERVER。
     */
    public static class Common {
        // --- 主开关 ---
        public final ModConfigSpec.BooleanValue PRESERVE_ITEMS_ENABLED;

        // --- 随机保留 (Randomizer) ---
        public final ModConfigSpec.BooleanValue RANDOMIZER_ENABLED;
        public final ModConfigSpec.IntValue RANDOMIZER_CHANCE;
        public final ModConfigSpec.IntValue LUCK_ADDITIVE;
        public final ModConfigSpec.DoubleValue LUCK_MULTIPLIER;

        // --- NBT 检查 ---
        public final ModConfigSpec.BooleanValue NBT_ENABLED;
        public final ModConfigSpec.ConfigValue<String> NBT_KEY;

        // --- 总是保留/丢弃列表 ---
        public final ModConfigSpec.ConfigValue<List<? extends String>> ALWAYS_PRESERVED_ITEMS;
        public final ModConfigSpec.ConfigValue<List<? extends String>> ALWAYS_PRESERVED_TAGS;
        public final ModConfigSpec.ConfigValue<List<? extends String>> ALWAYS_DROPPED_ITEMS;
        public final ModConfigSpec.ConfigValue<List<? extends String>> ALWAYS_DROPPED_TAGS;

        // --- 按类型分类 (Item Type) ---
        public final ModConfigSpec.BooleanValue BY_ITEM_TYPE_ENABLED;

        // 具体的类型策略
        public final ModConfigSpec.EnumValue<TypeBehavior> HELMETS;
        public final ModConfigSpec.EnumValue<TypeBehavior> CHESTPLATES;
        public final ModConfigSpec.EnumValue<TypeBehavior> LEGGINGS;
        public final ModConfigSpec.EnumValue<TypeBehavior> BOOTS;
        public final ModConfigSpec.EnumValue<TypeBehavior> ELYTRAS;
        public final ModConfigSpec.EnumValue<TypeBehavior> SHIELDS;
        public final ModConfigSpec.EnumValue<TypeBehavior> TOOLS; // 简化：包括稿、铲、斧、锄
        public final ModConfigSpec.EnumValue<TypeBehavior> WEAPONS; // 简化：包括剑、三叉戟、弓
        public final ModConfigSpec.EnumValue<TypeBehavior> MELEE_WEAPONS;
        public final ModConfigSpec.EnumValue<TypeBehavior> RANGED_WEAPONS;
        public final ModConfigSpec.EnumValue<TypeBehavior> UTILITY_TOOLS;
        public final ModConfigSpec.EnumValue<TypeBehavior> FISHING_RODS;
        public final ModConfigSpec.EnumValue<TypeBehavior> BUCKETS;
        public final ModConfigSpec.EnumValue<TypeBehavior> ENCHANTED_BOOKS;
        public final ModConfigSpec.EnumValue<TypeBehavior> TOTEMS;
        public final ModConfigSpec.EnumValue<TypeBehavior> BLOCK_ITEMS;
        public final ModConfigSpec.EnumValue<TypeBehavior> SPAWN_EGGS;
        public final ModConfigSpec.EnumValue<TypeBehavior> ARROWS;
        public final ModConfigSpec.EnumValue<TypeBehavior> FOOD;
        public final ModConfigSpec.EnumValue<TypeBehavior> POTIONS;
        public final ModConfigSpec.EnumValue<TypeBehavior> CURIOS; // 饰品

        public final ModConfigSpec.BooleanValue DEATH_COORDS_ENABLED;
        public final ModConfigSpec.BooleanValue ITEM_GLOW_ENABLED;
        public final ModConfigSpec.IntValue PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS;
        public final ModConfigSpec.DoubleValue PRIVATE_HIGHLIGHT_SCAN_RADIUS;
        public final ModConfigSpec.IntValue PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES;

        // --- 发光可见性 ---
        public final ModConfigSpec.EnumValue<GlowVisibility> GLOW_VISIBILITY;
        public final ModConfigSpec.BooleanValue NO_TEAM_IS_VALID_TEAM;

        // --- 物品韧性（分项） ---
        public final ModConfigSpec.BooleanValue ITEM_RESILIENCE_ENABLED;
        public final ModConfigSpec.BooleanValue DEATH_ITEMS_FIRE_PROOF;
        public final ModConfigSpec.BooleanValue DEATH_ITEMS_CACTUS_PROOF;
        public final ModConfigSpec.BooleanValue DEATH_ITEMS_EXPLOSION_PROOF;

        // --- 延长死亡物品寿命 ---
        public final ModConfigSpec.BooleanValue EXTENDED_LIFETIME_ENABLED;
        public final ModConfigSpec.IntValue DEATH_DROP_ITEM_LIFETIME_SECONDS;
        public final ModConfigSpec.BooleanValue DEATH_DROP_ITEMS_NEVER_DESPAWN;

        public final ModConfigSpec.BooleanValue VOID_RECOVERY_ENABLED;
        public final ModConfigSpec.BooleanValue HAZARD_RECOVERY_ENABLED;
        public final ModConfigSpec.EnumValue<VoidRecoveryMode> VOID_RECOVERY_MODE;
        public final ModConfigSpec.IntValue VOID_RECOVERY_WINDOW_TICKS;
        public final ModConfigSpec.IntValue VOID_RECOVERY_MAX_RECOVERIES;
        public final ModConfigSpec.IntValue VOID_RECOVERY_COOLDOWN_TICKS;
        public final ModConfigSpec.BooleanValue RESTORE_SLOTS_ENABLED;

        @SuppressWarnings({"deprecation", "null"}) // deprecation: defineList 旧版重载; null: MC API 误报
        public Common(ModConfigSpec.Builder builder) {
            builder.push("General");
            PRESERVE_ITEMS_ENABLED = builder.comment(
                    "Master switch: preserve items on death / 死亡保留物品总开关\n"
                    + "关闭后所有保留规则均不生效，物品正常掉落").define("enabled", true);
            builder.pop();

            builder.push("Randomizer");
            RANDOMIZER_ENABLED = builder.comment(
                    "Enable random preservation for items not matched by other rules\n"
                    + "对未被其他规则命中的物品启用随机保留").define("enabled", false);
            RANDOMIZER_CHANCE = builder.comment(
                    "Base random keep chance (percent, 0–100)\n"
                    + "基础随机保留概率（百分比），如 25 表示 25%").defineInRange("chancePercent", 25, 0, 100);
            LUCK_ADDITIVE = builder.comment(
                    "Luck additive factor (percent, 0–100)\n"
                    + "幸运值加算因子（百分比），如 20 表示每点幸运额外加 20%").defineInRange("luckAdditive", 20, 0, 100);
            LUCK_MULTIPLIER = builder.comment(
                    "Luck multiplier factor (0.0–10.0)\n"
                    + "幸运值乘算因子，公式：chance × (1 + multiplier × luck) + additive × luck\n"
                    + "示例：luck=2, multiplier=1.0, additive=20, chance=25 → 25%×(1+2)+40%=115%→截取100%").defineInRange("luckMultiplier", 1.0, 0.0, 10.0);
            builder.pop();

            builder.push("NBT");
            NBT_ENABLED = builder.comment(
                    "Preserve items with a specific NBT boolean tag (soulbound-like)\n"
                    + "根据 NBT 布尔标记保留物品（类似灵魂绑定）\n"
                    + "示例：/give @s diamond{Soulbound:1b} → 该钻石始终保留").define("enabled", false);
            NBT_KEY = builder.comment(
                    "NBT boolean key name used for soulbound check\n"
                    + "灵魂绑定检查使用的 NBT 布尔键名\n"
                    + "示例：若设为 \"Soulbound\"，则物品 CustomData 中包含 {Soulbound:1b} 时视为绑定").define("nbtKey", "Soulbound");
            builder.pop();

            builder.push("Lists");
            ALWAYS_PRESERVED_ITEMS = builder.comment(
                    "Item IDs that are always preserved on death\n"
                    + "始终保留的物品 ID 列表\n"
                    + "示例：[\"minecraft:totem_of_undying\", \"minecraft:elytra\"]")
                    .defineList("alwaysPreservedItems", List.of(), o -> o instanceof String);
            ALWAYS_PRESERVED_TAGS = builder.comment(
                    "Item tags whose items are always preserved\n"
                    + "始终保留的物品标签列表，命中该标签的物品将被保留\n"
                    + "示例：[\"minecraft:logs\", \"lenientdeath:safe\"]")
                    .defineList("alwaysPreservedTags", List.of("lenientdeath:safe"), o -> o instanceof String);
            ALWAYS_DROPPED_ITEMS = builder.comment(
                    "Item IDs that are always dropped on death (overrides preservation)\n"
                    + "始终掉落的物品 ID 列表（优先级高于保留）\n"
                    + "示例：[\"minecraft:diamond\"]")
                .defineList("alwaysDroppedItems", List.of(), o -> o instanceof String);
            ALWAYS_DROPPED_TAGS = builder.comment(
                    "Item tags whose items are always dropped\n"
                    + "始终掉落的物品标签列表\n"
                    + "示例：[\"minecraft:planks\"]")
                .defineList("alwaysDroppedTags", List.of(), o -> o instanceof String);
            builder.pop();

            builder.push("ItemTypes");
            BY_ITEM_TYPE_ENABLED = builder.comment(
                    "Enable item type rules (armor, tools, weapons, food, etc.)\n"
                    + "启用按物品类型（护甲、工具、武器、食物等）分类处理\n"
                    + "每个类型可设为：PRESERVE（保留）/ DROP（掉落）/ IGNORE（不做特殊处理）").define("enabled", true);

            HELMETS = builder.comment("Rule for helmets / 头盔规则").defineEnum("helmets", TypeBehavior.PRESERVE);
            CHESTPLATES = builder.comment("Rule for chestplates / 胸甲规则").defineEnum("chestplates", TypeBehavior.PRESERVE);
            LEGGINGS = builder.comment("Rule for leggings / 护腿规则").defineEnum("leggings", TypeBehavior.PRESERVE);
            BOOTS = builder.comment("Rule for boots / 靴子规则").defineEnum("boots", TypeBehavior.PRESERVE);
            ELYTRAS = builder.comment("Rule for elytras / 鞘翅规则").defineEnum("elytras", TypeBehavior.PRESERVE);
            SHIELDS = builder.comment("Rule for shields / 盾牌规则").defineEnum("shields", TypeBehavior.PRESERVE);

            TOOLS = builder.comment("Pickaxes, axes, shovels, hoes / 工具类规则").defineEnum("tools", TypeBehavior.PRESERVE);
            WEAPONS = builder.comment("All weapons (melee + ranged) / 武器总规则（近战+远程）").defineEnum("weapons", TypeBehavior.PRESERVE);
            MELEE_WEAPONS = builder.comment("Swords, maces, tridents / 近战武器规则").defineEnum("meleeWeapons", TypeBehavior.IGNORE);
            RANGED_WEAPONS = builder.comment("Bows, crossbows, projectile weapons / 远程武器规则").defineEnum("rangedWeapons", TypeBehavior.IGNORE);
            UTILITY_TOOLS = builder.comment("Shears, flint and steel, etc / 功能工具规则").defineEnum("utilityTools", TypeBehavior.IGNORE);
            FISHING_RODS = builder.comment("Fishing rods / 钓鱼竿规则").defineEnum("fishingRods", TypeBehavior.IGNORE);
            BUCKETS = builder.comment("Buckets including milk bucket / 桶类物品规则").defineEnum("buckets", TypeBehavior.IGNORE);
            ENCHANTED_BOOKS = builder.comment("Enchanted books / 附魔书规则").defineEnum("enchantedBooks", TypeBehavior.IGNORE);
            TOTEMS = builder.comment("Totem of Undying / 不死图腾规则").defineEnum("totems", TypeBehavior.IGNORE);
            BLOCK_ITEMS = builder.comment("Items that place blocks / 方块物品规则").defineEnum("blockItems", TypeBehavior.IGNORE);
            SPAWN_EGGS = builder.comment("Spawn eggs / 刷怪蛋规则").defineEnum("spawnEggs", TypeBehavior.IGNORE);
            ARROWS = builder.comment("Arrows, tipped/spectral arrows / 箭矢规则").defineEnum("arrows", TypeBehavior.IGNORE);

            FOOD = builder.comment("Rule for edible items / 食物规则").defineEnum("food", TypeBehavior.PRESERVE);
            POTIONS = builder.comment("Rule for potions / 药水规则").defineEnum("potions", TypeBehavior.PRESERVE);
            CURIOS = builder.comment("Curios/Trinkets support / Curios 饰品规则").defineEnum("curios", TypeBehavior.PRESERVE);
            builder.pop();

            builder.push("Features");
            DEATH_COORDS_ENABLED = builder.comment(
                    "Show death coordinates in chat after respawn\n"
                    + "重生后在聊天栏显示死亡坐标（含维度信息）").define("deathCoordinates", true);
            ITEM_GLOW_ENABLED = builder.comment(
                    "Enable private owner-only item glow highlight\n"
                    + "启用私有高亮：仅物品归属的玩家能看到掉落物发光，其他玩家看不到").define("itemGlow", true);
            PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS = builder.comment(
                    "Private highlight scan interval in ticks (1–200)\n"
                    + "私有高亮扫描间隔（tick），值越小刻新越快但服务器开销越大\n"
                    + "示例：10 = 每 0.5 秒扫描一次").defineInRange("privateHighlightScanIntervalTicks", 10, 1, 200);
            PRIVATE_HIGHLIGHT_SCAN_RADIUS = builder.comment(
                    "Private highlight scan radius in blocks (8.0–256.0)\n"
                    + "私有高亮扫描半径（方块），超出该范围的掉落物不会高亮").defineInRange("privateHighlightScanRadius", 96.0, 8.0, 256.0);
            PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES = builder.comment(
                    "Max item entities processed per highlight scan (16–4096)\n"
                    + "每次扫描最多处理的掉落物实体数，用于限制服务器开销").defineInRange("privateHighlightMaxScannedEntities", 256, 16, 4096);

            builder.push("DroppedItemGlow");
            GLOW_VISIBILITY = builder.comment(
                    "Who should see the glow on death drop items?\n"
                    + "DEAD_PLAYER = Only the player who died / 仅死亡玩家自己\n"
                    + "DEAD_PLAYER_AND_TEAM = The dead player and anyone on the same team / 死亡玩家及同队伍玩家\n"
                    + "EVERYONE = All online players / 所有在线玩家").defineEnum("glowVisibility", GlowVisibility.DEAD_PLAYER_AND_TEAM);
            NO_TEAM_IS_VALID_TEAM = builder.comment(
                    "Only applies if glowVisibility is DEAD_PLAYER_AND_TEAM.\n"
                    + "If the dead player isn't on a team, show outline to everyone without a team?\n"
                    + "Otherwise only shown to the dead player.\n"
                    + "当死亡玩家没有队伍时，是否对所有无队伍玩家显示高亮？").define("noTeamIsValidTeam", true);
            builder.pop();

            ITEM_RESILIENCE_ENABLED = builder.comment(
                    "Master switch: make death-dropped items invulnerable to all damage\n"
                    + "总开关：让死亡掉落物免疫所有伤害（开启时覆盖下方分项设置）").define("itemResilience", true);

            builder.push("ItemResilience");
            DEATH_ITEMS_FIRE_PROOF = builder.comment(
                    "Death drop items are immune to fire (only checked when master switch is off)\n"
                    + "死亡掉落物免疫火焰（仅在总开关关闭时生效）").define("allDeathItemsAreFireProof", false);
            DEATH_ITEMS_CACTUS_PROOF = builder.comment(
                    "Death drop items are immune to cactus (only checked when master switch is off)\n"
                    + "死亡掉落物免疫仙人掌（仅在总开关关闭时生效）").define("allDeathItemsAreCactusProof", false);
            DEATH_ITEMS_EXPLOSION_PROOF = builder.comment(
                    "Death drop items are immune to explosions (only checked when master switch is off)\n"
                    + "死亡掉落物免疫爆炸（仅在总开关关闭时生效）").define("allDeathItemsAreExplosionProof", false);
            builder.pop();

            builder.push("ExtendedDeathItemLifetime");
            EXTENDED_LIFETIME_ENABLED = builder.comment(
                    "Whether death drop's lifetime should be modified by LenientDeath\n"
                    + "是否修改死亡掉落物的存在时间").define("enabled", true);
            DEATH_DROP_ITEM_LIFETIME_SECONDS = builder.comment(
                    "How long death drop items should last in seconds (ignored if neverDespawn is true)\n"
                    + "死亡掉落物的存在时间（秒），neverDespawn 为 true 时忽略\n"
                    + "300 = 5 min (vanilla), 900 = 15 min, 1800 = 30 min").defineInRange("deathDropItemLifetimeSeconds", 900, 0, 1800);
            DEATH_DROP_ITEMS_NEVER_DESPAWN = builder.comment(
                    "If true, death drop items will never despawn\n"
                    + "为 true 时死亡掉落物永不消失\n"
                    + "清理命令: /kill @e[type=item,tag=LENIENT_DEATH_INFINITE_LIFETIME]").define("deathDropItemsNeverDespawn", true);
            builder.pop();

            VOID_RECOVERY_ENABLED = builder.comment(
                    "Recover items that fall into the void to a safe position\n"
                    + "启用虚空恢复：当掉落物落入虚空时传送到安全位置\n"
                    + "优先使用玩家历史安全点 → 三维搜索最近安全落点 → 出生点附近").define("voidRecovery", true);
            HAZARD_RECOVERY_ENABLED = builder.comment(
                    "Recover items from lava/fire to a safe position\n"
                    + "启用火焰/岩浆恢复：当掉落物着火或在岩浆中时传送到安全位置").define("hazardRecovery", true);
            VOID_RECOVERY_MODE = builder.comment(
                    "Void/Hazard recovery scope:\n"
                    + "DEATH_DROPS_ONLY = only recover death-dropped items (默认，仅恢复死亡掉落物)\n"
                    + "ALL_DROPS = recover all dropped items (恢复所有掉落物，包括主动丢弃的)").defineEnum("voidRecoveryMode", VoidRecoveryMode.DEATH_DROPS_ONLY);
            VOID_RECOVERY_WINDOW_TICKS = builder.comment(
                    "Rate-limit window for void recovery (ticks, 1–1200)\n"
                    + "虚空恢复限流窗口（tick），在该时间窗口内统计恢复次数\n"
                    + "示例：10 = 0.5 秒内最多恢复 voidRecoveryMaxRecoveries 次").defineInRange("voidRecoveryWindowTicks", 10, 1, 1200);
            VOID_RECOVERY_MAX_RECOVERIES = builder.comment(
                    "Max recoveries allowed inside one window (1–100)\n"
                    + "一个窗口内允许的最大恢复次数，超出后进入冷却").defineInRange("voidRecoveryMaxRecoveries", 3, 1, 100);
            VOID_RECOVERY_COOLDOWN_TICKS = builder.comment(
                    "Cooldown after hitting max recoveries (ticks, 1–1200)\n"
                    + "达到最大恢复次数后的冷却时长（tick）\n"
                    + "示例：10 = 冷却 0.5 秒后才能再次恢复").defineInRange("voidRecoveryCooldownTicks", 10, 1, 1200);
            RESTORE_SLOTS_ENABLED = builder.comment(
                    "Try to restore preserved items to their original inventory slots\n"
                    + "尝试将保留的物品放回死亡前的原始槽位（如工具栏、护甲栏等）").define("restoreSlots", true);
            builder.pop();
        }
    }

    /**
     * 物品类型策略：组合时 DROP 优先级最高，其次 PRESERVE，最后 IGNORE。
     * <ul>
     *   <li>任何命中 DROP → 最终 DROP</li>
     *   <li>无 DROP 但命中 PRESERVE → 最终 PRESERVE</li>
     *   <li>全部 IGNORE → IGNORE</li>
     * </ul>
     */
    public enum TypeBehavior {
        DROP, PRESERVE, IGNORE;

        /** 将两个策略按优先级合并。 */
        public TypeBehavior and(TypeBehavior other) {
            if (this == DROP || other == DROP) return DROP;
            if (this == PRESERVE || other == PRESERVE) return PRESERVE;
            return IGNORE;
        }
    }

    /** 虚空恢复模式：仅死亡掉落物 或 所有掉落物。 */
    public enum VoidRecoveryMode {
        DEATH_DROPS_ONLY,
        ALL_DROPS
    }

    /** 发光高亮可见性模式。 */
    public enum GlowVisibility {
        /** 仅死亡玩家自己可见。 */
        DEAD_PLAYER,
        /** 死亡玩家及同队伍玩家可见。 */
        DEAD_PLAYER_AND_TEAM,
        /** 所有在线玩家可见。 */
        EVERYONE
    }

    static {
        Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
        SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }
}