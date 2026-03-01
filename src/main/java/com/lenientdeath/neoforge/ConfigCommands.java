package com.lenientdeath.neoforge;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 注册并处理 {@code /lenientdeath} 命令树，包括配置读写、玩家列表操作和调试信息。
 */
@SuppressWarnings("null") // Minecraft API 的 @Nullable 注解误报
public final class ConfigCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/Commands");

    private ConfigCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set")
                .then(booleanSetting("enabled", Config.COMMON.PRESERVE_ITEMS_ENABLED))
                .then(booleanSetting("byItemTypeEnabled", Config.COMMON.BY_ITEM_TYPE_ENABLED))
                .then(booleanSetting("deathCoordinates", Config.COMMON.DEATH_COORDS_ENABLED))
                .then(booleanSetting("itemGlow", Config.COMMON.ITEM_GLOW_ENABLED))
                .then(enumSetting("glowVisibility", Config.COMMON.GLOW_VISIBILITY))
                .then(booleanSetting("noTeamIsValidTeam", Config.COMMON.NO_TEAM_IS_VALID_TEAM))
                .then(booleanSetting("itemResilience", Config.COMMON.ITEM_RESILIENCE_ENABLED))
                .then(booleanSetting("allDeathItemsAreFireProof", Config.COMMON.DEATH_ITEMS_FIRE_PROOF))
                .then(booleanSetting("allDeathItemsAreCactusProof", Config.COMMON.DEATH_ITEMS_CACTUS_PROOF))
                .then(booleanSetting("allDeathItemsAreExplosionProof", Config.COMMON.DEATH_ITEMS_EXPLOSION_PROOF))
                .then(booleanSetting("extendedLifetimeEnabled", Config.COMMON.EXTENDED_LIFETIME_ENABLED))
                .then(intSetting("deathDropItemLifetimeSeconds", Config.COMMON.DEATH_DROP_ITEM_LIFETIME_SECONDS, 0, 1800))
                .then(booleanSetting("deathDropItemsNeverDespawn", Config.COMMON.DEATH_DROP_ITEMS_NEVER_DESPAWN))
                .then(booleanSetting("voidRecovery", Config.COMMON.VOID_RECOVERY_ENABLED))
                .then(booleanSetting("hazardRecovery", Config.COMMON.HAZARD_RECOVERY_ENABLED))
                .then(Commands.literal("voidRecoveryDebug")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean oldValue = DeathEventHandler.getVoidRecoveryDebug();
                                    boolean newValue = BoolArgumentType.getBool(context, "value");
                                    DeathEventHandler.setVoidRecoveryDebug(newValue);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "lenientdeath.command.config.set.applied", "voidRecoveryDebug",
                                            String.valueOf(newValue), String.valueOf(oldValue)), true);
                                    return 1;
                                })))
                .then(enumSetting("voidRecoveryMode", Config.COMMON.VOID_RECOVERY_MODE))
                .then(booleanSetting("restoreSlots", Config.COMMON.RESTORE_SLOTS_ENABLED))
                .then(intSetting("privateHighlightScanIntervalTicks", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS, 1, 200))
                .then(doubleSetting("privateHighlightScanRadius", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_RADIUS, 8.0, 256.0))
                .then(intSetting("privateHighlightMaxScannedEntities", Config.COMMON.PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES, 16, 4096))
                .then(intSetting("voidRecoveryWindowTicks", Config.COMMON.VOID_RECOVERY_WINDOW_TICKS, 1, 1200))
                .then(intSetting("voidRecoveryMaxRecoveries", Config.COMMON.VOID_RECOVERY_MAX_RECOVERIES, 1, 100))
                .then(intSetting("voidRecoveryCooldownTicks", Config.COMMON.VOID_RECOVERY_COOLDOWN_TICKS, 1, 1200));

        LiteralArgumentBuilder<CommandSourceStack> get = Commands.literal("get")
                .then(booleanGetter("enabled", Config.COMMON.PRESERVE_ITEMS_ENABLED))
                .then(booleanGetter("byItemTypeEnabled", Config.COMMON.BY_ITEM_TYPE_ENABLED))
                .then(booleanGetter("deathCoordinates", Config.COMMON.DEATH_COORDS_ENABLED))
                .then(booleanGetter("itemGlow", Config.COMMON.ITEM_GLOW_ENABLED))
                .then(enumGetter("glowVisibility", Config.COMMON.GLOW_VISIBILITY))
                .then(booleanGetter("noTeamIsValidTeam", Config.COMMON.NO_TEAM_IS_VALID_TEAM))
                .then(booleanGetter("itemResilience", Config.COMMON.ITEM_RESILIENCE_ENABLED))
                .then(booleanGetter("allDeathItemsAreFireProof", Config.COMMON.DEATH_ITEMS_FIRE_PROOF))
                .then(booleanGetter("allDeathItemsAreCactusProof", Config.COMMON.DEATH_ITEMS_CACTUS_PROOF))
                .then(booleanGetter("allDeathItemsAreExplosionProof", Config.COMMON.DEATH_ITEMS_EXPLOSION_PROOF))
                .then(booleanGetter("extendedLifetimeEnabled", Config.COMMON.EXTENDED_LIFETIME_ENABLED))
                .then(intGetter("deathDropItemLifetimeSeconds", Config.COMMON.DEATH_DROP_ITEM_LIFETIME_SECONDS, 0, 1800))
                .then(booleanGetter("deathDropItemsNeverDespawn", Config.COMMON.DEATH_DROP_ITEMS_NEVER_DESPAWN))
                .then(booleanGetter("voidRecovery", Config.COMMON.VOID_RECOVERY_ENABLED))
                .then(booleanGetter("hazardRecovery", Config.COMMON.HAZARD_RECOVERY_ENABLED))
                .then(Commands.literal("voidRecoveryDebug")
                        .executes(context -> {
                            boolean val = DeathEventHandler.getVoidRecoveryDebug();
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "lenientdeath.command.config.get.value", "voidRecoveryDebug", String.valueOf(val)), false);
                            return 1;
                        }))
                .then(enumGetter("voidRecoveryMode", Config.COMMON.VOID_RECOVERY_MODE))
                .then(booleanGetter("restoreSlots", Config.COMMON.RESTORE_SLOTS_ENABLED))
                .then(intGetter("privateHighlightScanIntervalTicks", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS, 1, 200))
                .then(doubleGetter("privateHighlightScanRadius", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_RADIUS, 8.0, 256.0))
                .then(intGetter("privateHighlightMaxScannedEntities", Config.COMMON.PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES, 16, 4096))
                .then(intGetter("voidRecoveryWindowTicks", Config.COMMON.VOID_RECOVERY_WINDOW_TICKS, 1, 1200))
                .then(intGetter("voidRecoveryMaxRecoveries", Config.COMMON.VOID_RECOVERY_MAX_RECOVERIES, 1, 100))
                .then(intGetter("voidRecoveryCooldownTicks", Config.COMMON.VOID_RECOVERY_COOLDOWN_TICKS, 1, 1200));

        // --- preserve: 始终保留物品/标签管理 ---
        LiteralArgumentBuilder<CommandSourceStack> preserve = Commands.literal("preserve")
                .then(Commands.literal("item")
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> addStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_PRESERVED_ITEMS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.preserve.item.added",
                                "lenientdeath.command.config.preserve.item.exists"
                            ))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> removeStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_PRESERVED_ITEMS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.preserve.item.removed",
                                "lenientdeath.command.config.preserve.item.missing"
                            ))))
                    .then(Commands.literal("list")
                        .executes(context -> listStringEntries(
                            context.getSource(),
                            Config.COMMON.ALWAYS_PRESERVED_ITEMS,
                            "lenientdeath.command.config.preserve.item.list.header",
                            "lenientdeath.command.config.preserve.list.empty"
                        )))
                )
                .then(Commands.literal("tag")
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> addStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_PRESERVED_TAGS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.preserve.tag.added",
                                "lenientdeath.command.config.preserve.tag.exists"
                            ))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> removeStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_PRESERVED_TAGS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.preserve.tag.removed",
                                "lenientdeath.command.config.preserve.tag.missing"
                            ))))
                    .then(Commands.literal("list")
                        .executes(context -> listStringEntries(
                            context.getSource(),
                            Config.COMMON.ALWAYS_PRESERVED_TAGS,
                            "lenientdeath.command.config.preserve.tag.list.header",
                            "lenientdeath.command.config.preserve.list.empty"
                        )))
                );

        // --- drop: 始终掉落物品/标签管理 ---
        LiteralArgumentBuilder<CommandSourceStack> drop = Commands.literal("drop")
                .then(Commands.literal("item")
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> addStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_DROPPED_ITEMS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.drop.item.added",
                                "lenientdeath.command.config.drop.item.exists"
                            ))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> removeStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_DROPPED_ITEMS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.drop.item.removed",
                                "lenientdeath.command.config.drop.item.missing"
                            ))))
                    .then(Commands.literal("list")
                        .executes(context -> listStringEntries(
                            context.getSource(),
                            Config.COMMON.ALWAYS_DROPPED_ITEMS,
                            "lenientdeath.command.config.drop.item.list.header",
                            "lenientdeath.command.config.drop.list.empty"
                        )))
                )
                .then(Commands.literal("tag")
                    .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> addStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_DROPPED_TAGS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.drop.tag.added",
                                "lenientdeath.command.config.drop.tag.exists"
                            ))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> removeStringListEntry(
                                context.getSource(),
                                Config.COMMON.ALWAYS_DROPPED_TAGS,
                                StringArgumentType.getString(context, "id"),
                                "lenientdeath.command.config.drop.tag.removed",
                                "lenientdeath.command.config.drop.tag.missing"
                            ))))
                    .then(Commands.literal("list")
                        .executes(context -> listStringEntries(
                            context.getSource(),
                            Config.COMMON.ALWAYS_DROPPED_TAGS,
                            "lenientdeath.command.config.drop.tag.list.header",
                            "lenientdeath.command.config.drop.list.empty"
                        )))
                );

        // --- 注册命令树 ---
        event.getDispatcher().register(
                Commands.literal("lenientdeath")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("config")
                                .then(set)
                                .then(get)
                                .then(preserve)
                                .then(drop)
                                .then(Commands.literal("reload")
                                        .executes(context -> reloadFromFile(context.getSource()))))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("status")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.header"), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.shared_flags", DeathEventHandler.isSharedFlagsAccessorReady()), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.highlight_players", DeathEventHandler.getPrivateHighlightTrackedPlayerCount()), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.void_recovery_debug", DeathEventHandler.getVoidRecoveryDebug()), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.saved_items", DeathEventHandler.getSavedItemsPlayerCount()), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.snapshots", DeathEventHandler.getInventorySnapshotPlayerCount()), false);
                                            context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.debug.status.pending_death_pos", DeathEventHandler.getPendingDeathPositionPlayerCount()), false);
                                            return 1;
                                        })))
        );
    }

    /** 保存配置文件。 */
    private static int saveConfig(CommandSourceStack source) {
        try {
            Config.SPEC.save();
            return 1;
        } catch (Exception ex) {
            LOGGER.error("Failed to save config", ex);
            source.sendFailure(Component.translatable("lenientdeath.command.config.save.failed"));
            return 0;
        }
    }

    /** 从世界 serverconfig 目录重新加载配置文件。 */
    private static int reloadFromFile(CommandSourceStack source) {
        Path configPath = source.getServer().getServerDirectory().resolve("serverconfig").resolve("lenientdeath-server.toml");
        if (!Files.exists(configPath)) {
            source.sendFailure(Component.translatable("lenientdeath.command.config.reload.missing", configPath.toString()));
            return 0;
        }

        if (!reloadFromPath(configPath)) {
            source.sendFailure(Component.translatable("lenientdeath.command.config.reload.failed", configPath.toString()));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("lenientdeath.command.config.reload.success", configPath.toString()), true);
        return 1;
    }

    /**
     * 从指定路径加载 TOML 配置并应用到内存中的 {@link Config.COMMON}。
     * <p>
     * 由 {@link ConfigMigration} 和 reload 命令共同调用。
     *
     * @param configPath TOML 配置文件路径
     * @return 是否成功加载并应用
     */
    static boolean reloadFromPath(Path configPath) {
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(configPath).build()) {
            fileConfig.load();

            applyBoolean(fileConfig, "General.enabled", Config.COMMON.PRESERVE_ITEMS_ENABLED);

            applyBoolean(fileConfig, "Randomizer.enabled", Config.COMMON.RANDOMIZER_ENABLED);
            applyInt(fileConfig, "Randomizer.chancePercent", Config.COMMON.RANDOMIZER_CHANCE);
            applyInt(fileConfig, "Randomizer.luckAdditive", Config.COMMON.LUCK_ADDITIVE);
            applyDouble(fileConfig, "Randomizer.luckMultiplier", Config.COMMON.LUCK_MULTIPLIER);

            applyBoolean(fileConfig, "NBT.enabled", Config.COMMON.NBT_ENABLED);
            applyString(fileConfig, "NBT.nbtKey", Config.COMMON.NBT_KEY);

            applyStringList(fileConfig, "Lists.alwaysPreservedItems", Config.COMMON.ALWAYS_PRESERVED_ITEMS);
            applyStringList(fileConfig, "Lists.alwaysPreservedTags", Config.COMMON.ALWAYS_PRESERVED_TAGS);
            applyStringList(fileConfig, "Lists.alwaysDroppedItems", Config.COMMON.ALWAYS_DROPPED_ITEMS);
            applyStringList(fileConfig, "Lists.alwaysDroppedTags", Config.COMMON.ALWAYS_DROPPED_TAGS);

            applyBoolean(fileConfig, "ItemTypes.enabled", Config.COMMON.BY_ITEM_TYPE_ENABLED);

            applyEnum(fileConfig, "ItemTypes.helmets", Config.COMMON.HELMETS);
            applyEnum(fileConfig, "ItemTypes.chestplates", Config.COMMON.CHESTPLATES);
            applyEnum(fileConfig, "ItemTypes.leggings", Config.COMMON.LEGGINGS);
            applyEnum(fileConfig, "ItemTypes.boots", Config.COMMON.BOOTS);
            applyEnum(fileConfig, "ItemTypes.elytras", Config.COMMON.ELYTRAS);
            applyEnum(fileConfig, "ItemTypes.shields", Config.COMMON.SHIELDS);
            applyEnum(fileConfig, "ItemTypes.tools", Config.COMMON.TOOLS);
            applyEnum(fileConfig, "ItemTypes.weapons", Config.COMMON.WEAPONS);
            applyEnum(fileConfig, "ItemTypes.meleeWeapons", Config.COMMON.MELEE_WEAPONS);
            applyEnum(fileConfig, "ItemTypes.rangedWeapons", Config.COMMON.RANGED_WEAPONS);
            applyEnum(fileConfig, "ItemTypes.utilityTools", Config.COMMON.UTILITY_TOOLS);
            applyEnum(fileConfig, "ItemTypes.fishingRods", Config.COMMON.FISHING_RODS);
            applyEnum(fileConfig, "ItemTypes.buckets", Config.COMMON.BUCKETS);
            applyEnum(fileConfig, "ItemTypes.enchantedBooks", Config.COMMON.ENCHANTED_BOOKS);
            applyEnum(fileConfig, "ItemTypes.totems", Config.COMMON.TOTEMS);
            applyEnum(fileConfig, "ItemTypes.blockItems", Config.COMMON.BLOCK_ITEMS);
            applyEnum(fileConfig, "ItemTypes.spawnEggs", Config.COMMON.SPAWN_EGGS);
            applyEnum(fileConfig, "ItemTypes.arrows", Config.COMMON.ARROWS);
            applyEnum(fileConfig, "ItemTypes.food", Config.COMMON.FOOD);
            applyEnum(fileConfig, "ItemTypes.potions", Config.COMMON.POTIONS);
            applyEnum(fileConfig, "ItemTypes.curios", Config.COMMON.CURIOS);

            applyBoolean(fileConfig, "Features.deathCoordinates", Config.COMMON.DEATH_COORDS_ENABLED);
            applyBoolean(fileConfig, "Features.itemGlow", Config.COMMON.ITEM_GLOW_ENABLED);
            applyInt(fileConfig, "Features.privateHighlightScanIntervalTicks", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS);
            applyDouble(fileConfig, "Features.privateHighlightScanRadius", Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_RADIUS);
            applyInt(fileConfig, "Features.privateHighlightMaxScannedEntities", Config.COMMON.PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES);
            applyEnum(fileConfig, "DroppedItemGlow.glowVisibility", Config.COMMON.GLOW_VISIBILITY);
            applyBoolean(fileConfig, "DroppedItemGlow.noTeamIsValidTeam", Config.COMMON.NO_TEAM_IS_VALID_TEAM);
            applyBoolean(fileConfig, "Features.itemResilience", Config.COMMON.ITEM_RESILIENCE_ENABLED);
            applyBoolean(fileConfig, "ItemResilience.allDeathItemsAreFireProof", Config.COMMON.DEATH_ITEMS_FIRE_PROOF);
            applyBoolean(fileConfig, "ItemResilience.allDeathItemsAreCactusProof", Config.COMMON.DEATH_ITEMS_CACTUS_PROOF);
            applyBoolean(fileConfig, "ItemResilience.allDeathItemsAreExplosionProof", Config.COMMON.DEATH_ITEMS_EXPLOSION_PROOF);
            applyBoolean(fileConfig, "ExtendedDeathItemLifetime.enabled", Config.COMMON.EXTENDED_LIFETIME_ENABLED);
            applyInt(fileConfig, "ExtendedDeathItemLifetime.deathDropItemLifetimeSeconds", Config.COMMON.DEATH_DROP_ITEM_LIFETIME_SECONDS);
            applyBoolean(fileConfig, "ExtendedDeathItemLifetime.deathDropItemsNeverDespawn", Config.COMMON.DEATH_DROP_ITEMS_NEVER_DESPAWN);
            applyBoolean(fileConfig, "Features.voidRecovery", Config.COMMON.VOID_RECOVERY_ENABLED);
            applyBoolean(fileConfig, "Features.hazardRecovery", Config.COMMON.HAZARD_RECOVERY_ENABLED);
            // voidRecoveryDebug 为运行时标志，不从文件加载
            applyEnum(fileConfig, "Features.voidRecoveryMode", Config.COMMON.VOID_RECOVERY_MODE);
            applyInt(fileConfig, "Features.voidRecoveryWindowTicks", Config.COMMON.VOID_RECOVERY_WINDOW_TICKS);
            applyInt(fileConfig, "Features.voidRecoveryMaxRecoveries", Config.COMMON.VOID_RECOVERY_MAX_RECOVERIES);
            applyInt(fileConfig, "Features.voidRecoveryCooldownTicks", Config.COMMON.VOID_RECOVERY_COOLDOWN_TICKS);
            applyBoolean(fileConfig, "Features.restoreSlots", Config.COMMON.RESTORE_SLOTS_ENABLED);

            ManualAllowAndBlocklist.INSTANCE.refreshItems();

            Config.SPEC.save();
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to reload config from file {}", configPath, ex);
            return false;
        }
    }

    private static void applyBoolean(CommentedFileConfig fileConfig, String path, ModConfigSpec.BooleanValue target) {
        Object raw = fileConfig.get(path);
        if (raw instanceof Boolean value) {
            target.set(value);
        }
    }

    private static void applyInt(CommentedFileConfig fileConfig, String path, ModConfigSpec.IntValue target) {
        Object raw = fileConfig.get(path);
        if (raw instanceof Number value) {
            target.set(value.intValue());
        }
    }

    private static void applyDouble(CommentedFileConfig fileConfig, String path, ModConfigSpec.DoubleValue target) {
        Object raw = fileConfig.get(path);
        if (raw instanceof Number value) {
            target.set(value.doubleValue());
        }
    }

    private static void applyString(CommentedFileConfig fileConfig, String path, ModConfigSpec.ConfigValue<String> target) {
        Object raw = fileConfig.get(path);
        if (raw instanceof String value) {
            target.set(value);
        }
    }

    private static void applyStringList(CommentedFileConfig fileConfig, String path, ModConfigSpec.ConfigValue<List<? extends String>> target) {
        Object raw = fileConfig.get(path);
        if (raw instanceof List<?> list) {
            List<String> values = list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            target.set(values);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends Enum<E>> void applyEnum(CommentedFileConfig fileConfig, String path, ModConfigSpec.EnumValue<E> target) {
        Object raw = fileConfig.get(path);
        if (raw == null) {
            return;
        }

        // 使用 getDefault() 获取枚举类，避免在 config 尚未加载时调用 get() 抛出异常
        E defaultVal = target.getDefault();
        if (defaultVal != null && raw instanceof String enumName) {
            Class enumClass = defaultVal.getDeclaringClass();
            try {
                E parsed = (E) Enum.valueOf(enumClass, enumName);
                target.set(parsed);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> booleanSetting(String key, ModConfigSpec.BooleanValue value) {
        return Commands.literal(key)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean oldValue = value.get();
                            boolean newValue = BoolArgumentType.getBool(context, "value");
                            value.set(newValue);
                            int result = saveConfig(context.getSource());
                            if (result == 1) {
                                context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.set.applied", key, String.valueOf(newValue), String.valueOf(oldValue)), true);
                            }
                            LOGGER.debug("Config changed: {} {} -> {}", key, oldValue, newValue);
                            return result;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> booleanGetter(String key, ModConfigSpec.BooleanValue value) {
        return Commands.literal(key)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.get.value", key, String.valueOf(value.get())), false);
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intSetting(String key, ModConfigSpec.IntValue value, int min, int max) {
        return Commands.literal(key)
                .then(Commands.argument("value", IntegerArgumentType.integer(min, max))
                        .executes(context -> {
                            int oldValue = value.get();
                            int newValue = IntegerArgumentType.getInteger(context, "value");
                            value.set(newValue);
                            int result = saveConfig(context.getSource());
                            if (result == 1) {
                                context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.set.applied_range", key, String.valueOf(newValue), String.valueOf(oldValue), String.valueOf(min), String.valueOf(max)), true);
                            }
                            LOGGER.debug("Config changed: {} {} -> {}", key, oldValue, newValue);
                            return result;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intGetter(String key, ModConfigSpec.IntValue value, int min, int max) {
        return Commands.literal(key)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.get.value_range", key, String.valueOf(value.get()), String.valueOf(min), String.valueOf(max)), false);
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> doubleSetting(String key, ModConfigSpec.DoubleValue value, double min, double max) {
        return Commands.literal(key)
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(min, max))
                        .executes(context -> {
                            double oldValue = value.get();
                            double newValue = DoubleArgumentType.getDouble(context, "value");
                            value.set(newValue);
                            int result = saveConfig(context.getSource());
                            if (result == 1) {
                                context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.set.applied_range", key, String.valueOf(newValue), String.valueOf(oldValue), String.valueOf(min), String.valueOf(max)), true);
                            }
                            LOGGER.debug("Config changed: {} {} -> {}", key, oldValue, newValue);
                            return result;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> doubleGetter(String key, ModConfigSpec.DoubleValue value, double min, double max) {
        return Commands.literal(key)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.get.value_range", key, String.valueOf(value.get()), String.valueOf(min), String.valueOf(max)), false);
                    return 1;
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LiteralArgumentBuilder<CommandSourceStack> enumSetting(String key, ModConfigSpec.EnumValue<?> value) {
        // 从默认值获取枚举类所有常量用于命令补全（注册时 config 尚未加载，不能调用 get()）
        Enum<?>[] constants = ((Enum<?>) value.getDefault()).getDeclaringClass().getEnumConstants();
        return Commands.literal(key)
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            String remaining = builder.getRemaining().toUpperCase(java.util.Locale.ROOT);
                            for (Enum<?> e : constants) {
                                if (e.name().toUpperCase(java.util.Locale.ROOT).startsWith(remaining)) {
                                    builder.suggest(e.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Object oldValue = value.get();
                            String input = StringArgumentType.getString(context, "value");
                            if (!(oldValue instanceof Enum<?> oldEnum)) {
                                context.getSource().sendFailure(Component.translatable("lenientdeath.command.config.set.invalid", key, input));
                                return 0;
                            }

                            try {
                                Class<? extends Enum> enumClass = oldEnum.getDeclaringClass();
                                Enum<?> parsed = Enum.valueOf(enumClass, input.toUpperCase(java.util.Locale.ROOT));
                                ((ModConfigSpec.EnumValue) value).set(parsed);
                            } catch (IllegalArgumentException ex) {
                                context.getSource().sendFailure(Component.translatable("lenientdeath.command.config.set.invalid", key, input));
                                return 0;
                            }

                            int result = saveConfig(context.getSource());
                            if (result == 1) {
                                context.getSource().sendSuccess(() -> Component.translatable("lenientdeath.command.config.set.applied", key, String.valueOf(value.get()), String.valueOf(oldValue)), true);
                            }
                            LOGGER.debug("Config changed: {} {} -> {}", key, oldValue, value.get());
                            return result;
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enumGetter(String key, ModConfigSpec.EnumValue<?> value) {
        return Commands.literal(key)
                .executes(context -> {
                    String current = String.valueOf(value.get());
                    Enum<?>[] constants = value.get().getDeclaringClass().getEnumConstants();
                    StringBuilder validValues = new StringBuilder();
                    for (int i = 0; i < constants.length; i++) {
                        if (i > 0) validValues.append(", ");
                        validValues.append(constants[i].name());
                    }
                    context.getSource().sendSuccess(() -> Component.translatable(
                            "lenientdeath.command.config.get.value_enum", key, current, validValues.toString()), false);
                    return 1;
                });
    }

    private static int addStringListEntry(CommandSourceStack source,
                                          ModConfigSpec.ConfigValue<List<? extends String>> target,
                                          String entry,
                                          String successKey,
                                          String existsKey) {
        List<String> values = new ArrayList<>(target.get());
        if (values.contains(entry)) {
            source.sendFailure(Component.translatable(existsKey, entry));
            return 0;
        }

        values.add(entry);
        target.set(values);
        ManualAllowAndBlocklist.INSTANCE.refreshItems();

        int saved = saveConfig(source);
        if (saved == 1) {
            source.sendSuccess(() -> Component.translatable(successKey, entry), true);
        }
        return saved;
    }

    private static int removeStringListEntry(CommandSourceStack source,
                                             ModConfigSpec.ConfigValue<List<? extends String>> target,
                                             String entry,
                                             String successKey,
                                             String missingKey) {
        List<String> values = new ArrayList<>(target.get());
        if (!values.remove(entry)) {
            source.sendFailure(Component.translatable(missingKey, entry));
            return 0;
        }

        target.set(values);
        ManualAllowAndBlocklist.INSTANCE.refreshItems();

        int saved = saveConfig(source);
        if (saved == 1) {
            source.sendSuccess(() -> Component.translatable(successKey, entry), true);
        }
        return saved;
    }

    private static int listStringEntries(CommandSourceStack source,
                                         ModConfigSpec.ConfigValue<List<? extends String>> target,
                                         String headerKey,
                                         String emptyKey) {
        List<? extends String> values = target.get();
        source.sendSuccess(() -> Component.translatable(headerKey, values.size()), false);

        if (values.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(emptyKey), false);
            return 1;
        }

        for (String value : values) {
            source.sendSuccess(() -> Component.literal("- " + value), false);
        }
        return values.size();
    }
}
