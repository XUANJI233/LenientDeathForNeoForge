package com.lenientdeath.neoforge;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public class Config {
    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

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
        public final ModConfigSpec.BooleanValue ITEM_RESILIENCE_ENABLED;
        public final ModConfigSpec.BooleanValue VOID_RECOVERY_ENABLED;
        public final ModConfigSpec.EnumValue<VoidRecoveryMode> VOID_RECOVERY_MODE;
        public final ModConfigSpec.IntValue VOID_RECOVERY_WINDOW_TICKS;
        public final ModConfigSpec.IntValue VOID_RECOVERY_MAX_RECOVERIES;
        public final ModConfigSpec.IntValue VOID_RECOVERY_COOLDOWN_TICKS;
        public final ModConfigSpec.BooleanValue RESTORE_SLOTS_ENABLED;

        @SuppressWarnings({"deprecation", "null"})
        public Common(ModConfigSpec.Builder builder) {
            builder.push("General");
            PRESERVE_ITEMS_ENABLED = builder.comment("Enable preserving items on death / 是否启用死亡保留物品").define("enabled", true);
            builder.pop();

            builder.push("Randomizer");
            RANDOMIZER_ENABLED = builder.comment("Enable random preservation for leftover items / 是否对剩余物品启用随机保留").define("enabled", false);
            RANDOMIZER_CHANCE = builder.comment("Base random keep chance percent / 基础随机保留概率（百分比）").defineInRange("chancePercent", 25, 0, 100);
            LUCK_ADDITIVE = builder.comment("Luck additive factor percent / 幸运值附加因子（百分比）").defineInRange("luckAdditive", 20, 0, 100);
            LUCK_MULTIPLIER = builder.comment("Luck multiplier factor / 幸运值乘算因子").defineInRange("luckMultiplier", 0.0, 0.0, 10.0);
            builder.pop();

            builder.push("NBT");
            NBT_ENABLED = builder.comment("Preserve items with specific NBT tag / 是否根据 NBT 标记保留物品").define("enabled", false);
            NBT_KEY = builder.comment("NBT boolean key used for soulbound-like behavior / 用于灵魂绑定的 NBT 布尔键名").define("nbtKey", "Soulbound");
            builder.pop();

            builder.push("Lists");
            ALWAYS_PRESERVED_ITEMS = builder.comment("List of Item IDs to always preserve (e.g. 'minecraft:apple') / 始终保留的物品 ID 列表")
                    .defineList("alwaysPreservedItems", List.of(), o -> o instanceof String);
            ALWAYS_PRESERVED_TAGS = builder.comment("List of Item Tags to always preserve (e.g. 'minecraft:logs') / 始终保留的物品标签列表")
                    .defineList("alwaysPreservedTags", List.of("lenientdeath:safe"), o -> o instanceof String);
            ALWAYS_DROPPED_ITEMS = builder.comment("List of Item IDs to always drop / 始终掉落的物品 ID 列表")
                .defineList("alwaysDroppedItems", List.of(), o -> o instanceof String);
            ALWAYS_DROPPED_TAGS = builder.comment("List of Item Tags to always drop / 始终掉落的物品标签列表")
                .defineList("alwaysDroppedTags", List.of(), o -> o instanceof String);
            builder.pop();

            builder.push("ItemTypes");
            BY_ITEM_TYPE_ENABLED = builder.comment("Enable filtering by item type / 是否启用按物品类型规则筛选").define("enabled", true);

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
            DEATH_COORDS_ENABLED = builder.comment("Show death coordinates / 是否显示死亡坐标").define("deathCoordinates", true);
            ITEM_GLOW_ENABLED = builder.comment("Enable private owner-only item highlight / 是否启用仅归属玩家可见高亮").define("itemGlow", true);
            PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS = builder.comment("Private highlight scan interval (ticks) / 私有高亮扫描间隔（tick）").defineInRange("privateHighlightScanIntervalTicks", 10, 1, 200);
            PRIVATE_HIGHLIGHT_SCAN_RADIUS = builder.comment("Private highlight scan radius / 私有高亮扫描半径").defineInRange("privateHighlightScanRadius", 96.0, 8.0, 256.0);
            PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES = builder.comment("Max entities processed per highlight scan / 每次高亮扫描最大处理实体数").defineInRange("privateHighlightMaxScannedEntities", 256, 16, 4096);
            ITEM_RESILIENCE_ENABLED = builder.comment("Items are immune to fire/explosion / 掉落物防火防爆").define("itemResilience", true);
            VOID_RECOVERY_ENABLED = builder.comment("Recover void-dropped items to safe position / 启用虚空掉落物安全恢复").define("voidRecovery", true);
            VOID_RECOVERY_MODE = builder.comment("Void recovery mode: DEATH_DROPS_ONLY or ALL_DROPS / 虚空恢复模式：仅死亡掉落或全部掉落").defineEnum("voidRecoveryMode", VoidRecoveryMode.DEATH_DROPS_ONLY);
            VOID_RECOVERY_WINDOW_TICKS = builder.comment("Window ticks for void recovery limiter / 虚空恢复限流窗口（tick）").defineInRange("voidRecoveryWindowTicks", 10, 1, 1200);
            VOID_RECOVERY_MAX_RECOVERIES = builder.comment("Max recoveries in window before cooldown / 窗口内最大恢复次数").defineInRange("voidRecoveryMaxRecoveries", 3, 1, 100);
            VOID_RECOVERY_COOLDOWN_TICKS = builder.comment("Cooldown ticks after reaching max recoveries / 达到上限后的冷却时长（tick）").defineInRange("voidRecoveryCooldownTicks", 10, 1, 1200);
            RESTORE_SLOTS_ENABLED = builder.comment("Restore preserved/picked items to original slots / 还原到原始槽位").define("restoreSlots", true);
            builder.pop();
        }
    }

    public enum TypeBehavior {
        DROP, PRESERVE, IGNORE;

        // 组合逻辑：DROP 优先级最高，其次 PRESERVE，最后 IGNORE。
        // 示例：
        // - 任何命中 DROP -> 最终 DROP
        // - 都没有 DROP，但命中了 PRESERVE -> 最终 PRESERVE
        // - 全部 IGNORE -> IGNORE
        public TypeBehavior and(TypeBehavior other) {
            if (this == DROP || other == DROP) return DROP;
            if (this == PRESERVE || other == PRESERVE) return PRESERVE;
            return IGNORE;
        }
    }

    public enum VoidRecoveryMode {
        DEATH_DROPS_ONLY,
        ALL_DROPS
    }

    static {
        Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
        SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }
}