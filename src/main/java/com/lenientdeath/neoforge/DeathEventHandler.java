package com.lenientdeath.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 核心事件处理器：处理死亡物品保留、虚空/危险恢复、私有高亮、槽位还原等逻辑。
 * <p>
 * 所有 {@code @SubscribeEvent} 方法由 NeoForge 事件总线反射调用。
 */
@SuppressWarnings({"unused", "null"}) // unused: @SubscribeEvent 方法由事件总线反射调用; null: Minecraft API 的 @Nullable 注解误报
@EventBusSubscriber(modid = LenientDeathNeoForge.MODID)
public class DeathEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/DeathEventHandler");

    /** 保留物品记录：物品堆叠及其在背包中的原始槽位。 */
    private record SavedItem(ItemStack stack, int originalSlot) {}

    /** 恢复目标：安全传送的目标坐标及来源策略名称（用于调试日志）。 */
    private record RecoveryTarget(BlockPos pos, String source) {}

    /** 私有高亮记录：发光颜色及实体 UUID 字符串（用于跨维度/实体消失后的清理包）。 */
    private record HighlightEntry(ChatFormatting color, String entityUuidString) {}

    /** 同 tick 的恢复目标缓存键，使用分桶坐标提升附近掉落物共享命中。 */
    private record RecoveryTargetCacheKey(ResourceKey<Level> dimension, int bucketX, int bucketY, int bucketZ, UUID ownerId, GlobalPos safePos) {}

    /** 恢复目标缓存值。 */
    private record RecoveryTargetCacheValue(long gameTime, RecoveryTarget target) {}

    /** 服务器内部存储的死亡掉落快照。 */
    private record DeathDropSnapshot(int id,
                                     String playerName,
                                     ResourceKey<Level> dimension,
                                     BlockPos deathPos,
                                     long gameTime,
                                     int containerSize,
                                     Map<Integer, ItemStack> slotItems) {}

    /** 玩家维度的快照摘要（用于 UI 第一层列表）。 */
    public record DeathDropSnapshotPlayerSummary(UUID playerId,
                                                 String playerName,
                                                 int snapshotCount) {}

    /** 用于命令展示的快照摘要。 */
    public record DeathDropSnapshotSummary(int id,
                                           String playerName,
                                           ResourceKey<Level> dimension,
                                           BlockPos deathPos,
                                           int itemStacks,
                                           long gameTime) {}

    /** 用于命令查看/恢复的快照详情。 */
    public record DeathDropSnapshotView(DeathDropSnapshotSummary summary,
                                        int containerSize,
                                        Map<Integer, ItemStack> slotItems) {}

    // ── 常量 ──────────────────────────────────────────────────────

    /** 安全位置更新间隔（tick），每 0.5 秒记录一次。 */
    private static final int SAFE_POS_UPDATE_TICKS = 10;
    /** 安全位置历史记录上限。 */
    private static final int SAFE_POS_HISTORY_LIMIT = 12;
    /** 虚空恢复触发偏移量：物品 Y 低于 (minBuildHeight - offset) 时触发。 */
    private static final double VOID_RECOVERY_TRIGGER_OFFSET = 8.0;
    /** 即时虚空恢复判定余量：玩家死亡 Y 低于 (minBuildHeight + margin) 时，在掉落物生成时立即恢复。 */
    private static final double IMMEDIATE_VOID_RECOVERY_Y_MARGIN = 8.0;
    /** 单次安全点搜索最多检查的候选方块数，避免极端场景压垮主线程。 */
    private static final int MAX_SAFE_SPOT_CHECKS_PER_SEARCH = 4096;
    /** 同一 tick 内允许执行的昂贵三维搜索次数上限。 */
    private static final int MAX_EXPENSIVE_RECOVERY_SEARCHES_PER_TICK = 2;
    /** 恢复目标缓存最大条目数（LRU 自动淘汰）。 */
    private static final int MAX_RECOVERY_TARGET_CACHE_ENTRIES = 4096;
    /** 恢复目标缓存坐标分桶粒度（方块）。 */
    private static final int RECOVERY_CACHE_POSITION_GRANULARITY = 4;
    /** Entity shared flags 同步数据的 slot ID（固定为 0）。 */
    private static final int ENTITY_SHARED_FLAGS_DATA_ID = 0;
    /** Entity shared flags 中发光位的掩码。 */
    private static final byte GLOWING_FLAG_MASK = 0x40;
    /**
     * Number of distinct spread buckets used when scattering teleported items.
     * Each bucket maps to a unique horizontal velocity so items from the same death event
     * land within a ±({@value #TELEPORT_SPREAD_BUCKETS}/2 * {@value #TELEPORT_SPREAD_SCALE})
     * block radius rather than stacking at one coordinate.
     */
    private static final int TELEPORT_SPREAD_BUCKETS = 7;
    /** Blocks-per-tick scale for the item-spread velocity; max horizontal speed = ±{@code (TELEPORT_SPREAD_BUCKETS/2) * TELEPORT_SPREAD_SCALE}. */
    private static final double TELEPORT_SPREAD_SCALE = 0.03;
    /** Small upward velocity applied on teleport so items arc gently away from the floor surface. */
    private static final double TELEPORT_UPWARD_VELOCITY = 0.05;


    // ── 反射获取的访问器 ──────────────────────────────────────────

    /** 通过反射获取的 Entity.DATA_SHARED_FLAGS_ID，用于发送私有发光数据包。 */
    private static final EntityDataAccessor<Byte> SHARED_FLAGS_ACCESSOR = resolveSharedFlagsAccessor();
    /** 反射失败时只警告一次的标志位（volatile 保证多线程可见性）。 */
    private static volatile boolean SHARED_FLAGS_ACCESSOR_WARNED = false;

    /** VarHandle for ItemEntity.age, used to read the field without Field.getInt() overhead on every tick. */
    private static final VarHandle ITEM_ENTITY_AGE_HANDLE = resolveItemEntityAgeHandle();

    // ── 发光颜色队伍基础设施 ────────────────────────────────────

    /** 用于构造队伍数据包的虚拟记分板。 */
    private static final Scoreboard GLOW_COLOR_SCOREBOARD = new Scoreboard();

    /** 每种发光颜色对应的虚拟队伍（仅用于客户端数据包，不影响服务端记分板）。 */
    private static final Map<ChatFormatting, PlayerTeam> GLOW_COLOR_TEAMS = createGlowColorTeams();

    /**
     * 虚空恢复调试开关（仅运行时，不持久化到配置文件）。
     * <p>
     * 每次服务器/世界加载后自动重置为 {@code false}，需手动通过命令开启。
     */
    private static volatile boolean voidRecoveryDebug = false;

    // ── 高频事件配置缓存（避免在 onEntityTick 中每 tick 调用 ConfigValue#get()） ──
    // 在 onConfigLoaded() 中同步更新，由 ModConfigEvent.Loading / Reloading 触发。
    private static volatile boolean cachedVoidRecoveryEnabled = true;
    private static volatile boolean cachedHazardRecoveryEnabled = true;
    private static volatile Config.VoidRecoveryMode cachedVoidRecoveryMode = Config.VoidRecoveryMode.DEATH_DROPS_ONLY;

    // ── 运行时状态（按玩家 UUID 索引） ────────────────────────────

    /** 死亡时保留的物品，在重生（Clone 事件）时还原。 */
    private static final Map<UUID, List<SavedItem>> SAVED_ITEMS = new ConcurrentHashMap<>();
    /** 死亡时的背包快照，用于匹配掉落物的原始槽位。 */
    private static final Map<UUID, Map<Integer, ItemStack>> INVENTORY_SNAPSHOTS = new ConcurrentHashMap<>();
    /** 玩家安全位置的历史队列，用于恢复时查找最近的安全点。 */
    private static final Map<UUID, Deque<GlobalPos>> SAFE_POS_HISTORY = new ConcurrentHashMap<>();
    /** 待发送的死亡坐标消息（等到重生后发送更稳定）。 */
    private static final Map<UUID, GlobalPos> PENDING_DEATH_POS = new ConcurrentHashMap<>();
    /** 每个玩家当前可见的私有高亮实体及其颜色（实体 ID → 高亮记录）。 */
    private static final Map<UUID, Map<Integer, HighlightEntry>> PRIVATE_HIGHLIGHT_COLORS = new ConcurrentHashMap<>();
    /** 每个玩家的死亡掉落快照（头部为最新）。 */
    private static final Map<UUID, Deque<DeathDropSnapshot>> DEATH_DROP_SNAPSHOTS = new ConcurrentHashMap<>();
    /** 每个玩家的自增快照 ID。 */
    private static final Map<UUID, Integer> NEXT_DEATH_DROP_SNAPSHOT_ID = new ConcurrentHashMap<>();
    /** 离线计划恢复队列（玩家 UUID -> 待恢复的快照 ID 列表）。 */
    private static final Map<UUID, Deque<Integer>> PENDING_SNAPSHOT_RESTORES = new ConcurrentHashMap<>();
    /** 每个玩家拥有的死亡掉落实体 ID，用于私有高亮增量扫描，避免全世界实体查询。 */
    private static final Map<UUID, Set<Integer>> OWNED_DEATH_DROP_IDS = new ConcurrentHashMap<>();
    /** 每个已追踪的掉落实体 ID 所在的维度键，用于跨维度正确查找实体。 */
    private static final Map<Integer, ResourceKey<Level>> ENTITY_DIMENSIONS = new ConcurrentHashMap<>();
    /** 归属玩家 UUID -> 记分板名称缓存，避免离线时触发 ProfileCache 查询。 */
    private static final Map<UUID, String> OWNER_SCOREBOARD_NAMES = new ConcurrentHashMap<>();
    /** 已向哪些玩家发送过发光颜色队伍创建包。 */
    private static final Set<UUID> GLOW_TEAMS_INITIALIZED = ConcurrentHashMap.newKeySet();
    /** 恢复目标 LRU 缓存，自动淘汰最久未使用条目。 */
    private static final Map<RecoveryTargetCacheKey, RecoveryTargetCacheValue> RECOVERY_TARGET_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RecoveryTargetCacheKey, RecoveryTargetCacheValue> eldest) {
                    return size() > MAX_RECOVERY_TARGET_CACHE_ENTRIES;
                }
            });
    /** 当前统计窗口的游戏时间（用于按 tick 重置昂贵搜索计数）。 */
    private static volatile long recoverySearchBudgetTick = Long.MIN_VALUE;
    /** 当前 tick 已执行的昂贵搜索次数（AtomicInteger 保证 ++ 操作的原子性）。 */
    private static final AtomicInteger expensiveRecoverySearchesThisTick = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> resolveSharedFlagsAccessor() {
        try {
            var field = Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
            field.setAccessible(true);
            return (EntityDataAccessor<Byte>) field.get(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to resolve Entity DATA_SHARED_FLAGS_ID, private glow will be disabled", e);
            return null;
        }
    }

    /**
     * Obtain a VarHandle for {@code ItemEntity.age}.
     * <p>
     * VarHandle gives access semantics equivalent to {@link Field#getInt} but is compiled by the JIT
     * as a direct field read without the per-call access-check overhead, which matters because
     * {@link #getItemEntityAge} is invoked from the glow-colour scan every few ticks per tracked item.
     */
    private static VarHandle resolveItemEntityAgeHandle() {
        try {
            Field field = ItemEntity.class.getDeclaredField("age");
            field.setAccessible(true);
            // unreflectVarHandle honours the already-set accessible flag, so no module-open is needed.
            return MethodHandles.lookup().unreflectVarHandle(field);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to resolve ItemEntity.age VarHandle, glow color will default to green", e);
            return null;
        }
    }

    /** 创建每种发光颜色对应的虚拟队伍（仅用于客户端数据包中的轮廓颜色）。 */
    private static Map<ChatFormatting, PlayerTeam> createGlowColorTeams() {
        Map<ChatFormatting, PlayerTeam> teams = new HashMap<>();
        ChatFormatting[] colors = {
            ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.YELLOW,
            ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_RED
        };
        for (ChatFormatting color : colors) {
            PlayerTeam team = new PlayerTeam(GLOW_COLOR_SCOREBOARD, "ld_" + color.getName());
            team.setColor(color);
            team.setNameTagVisibility(Team.Visibility.NEVER);
            teams.put(color, team);
        }
        return teams;
    }

    /** Reads {@code ItemEntity.age} via the pre-resolved VarHandle (JIT-inlinable, no per-call access check). */
    private static int getItemEntityAge(ItemEntity item) {
        if (ITEM_ENTITY_AGE_HANDLE == null) return 0;
        return (int) ITEM_ENTITY_AGE_HANDLE.get(item);
    }

    /**
     * 根据物品剩余寿命计算发光颜色。
     * <ul>
     *   <li>蓝色：剩余 &gt; 5 min（仅延长寿命时可见）</li>
     *   <li>绿色：[3 min, 5 min)</li>
     *   <li>黄色：[2 min, 3 min)</li>
     *   <li>橙色：[1 min, 2 min)</li>
     *   <li>红色：[30 sec, 1 min)</li>
     *   <li>闪烁红：[0, 30 sec)（每约 0.5 秒交替红/暗红）</li>
     * </ul>
     */
    private static ChatFormatting getGlowColorForItem(ItemEntity item) {
        int age = getItemEntityAge(item);
        int lifespan = item.lifespan;
        int remainingTicks = lifespan - age;

        // 无限寿命 (age < 0 表示 setUnlimitedLifetime)
        if (age < 0 || remainingTicks > 6000) return ChatFormatting.BLUE;
        if (remainingTicks >= 3600) return ChatFormatting.GREEN;
        if (remainingTicks >= 2400) return ChatFormatting.YELLOW;
        if (remainingTicks >= 1200) return ChatFormatting.GOLD;  // 橙色近似
        if (remainingTicks >= 600) return ChatFormatting.RED;
        // 闪烁红：每 10 tick 交替
        return (item.tickCount / 10) % 2 == 0 ? ChatFormatting.RED : ChatFormatting.DARK_RED;
    }

    /**
     * 玩家 tick 后处理：记录安全位置 & 刷新私有高亮。
     * <p>
     * 安全位置每 {@value SAFE_POS_UPDATE_TICKS} tick 更新一次，仅在地面上时记录。
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide) return;

        int privateHighlightIntervalTicks = getPrivateHighlightIntervalTicks();

        if (Config.COMMON.ITEM_GLOW_ENABLED.get()) {
            if (player.tickCount % privateHighlightIntervalTicks == 0) {
                refreshPrivateHighlights(player);
            }
        } else if (player.tickCount % privateHighlightIntervalTicks == 0) {
            clearPrivateHighlights(player);
            // 高亮功能关闭时 refreshPrivateHighlights 不会被调用，需要手动清理已消失的掉落物 ID
            // 避免玩家多次死亡后 OWNED_DEATH_DROP_IDS 无限膨胀
            cleanupStaleOwnedDropIds(player);
        }

        // 只有玩家站在地面上，且不是观察者模式时，才记录安全点历史
        if (player.tickCount % SAFE_POS_UPDATE_TICKS == 0 && player.onGround() && !player.isSpectator() && player.level() instanceof ServerLevel serverLevel) {
            BlockPos currentBlockPos = player.blockPosition();
            BlockPos safePos = resolveStandingSafePos(currentBlockPos);
            GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), safePos.immutable());
            pushSafePosHistory(player.getUUID(), currentPos);
            ModEntityData.put(player, ModAttachments.SAFE_RECOVERY_POS, currentPos);
        }

        // 拾取逻辑由 ItemEntityPickupEvent 处理，不在此处做周期性扫描。
    }

    /**
     * 物品拾取前处理：尝试将带有原始槽位标记的物品还原到原始背包位置。
     */
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        ItemEntity entity = event.getItemEntity();

        // 物品被拾取时立即从归属索引移除，避免私有高亮持续扫描无效实体 ID
        untrackOwnedDropEntity(entity);

        if (!Config.COMMON.RESTORE_SLOTS_ENABLED.get()) return;

        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        if (!ModEntityData.has(entity, ModAttachments.ORIGINAL_SLOT)) return;

        int targetSlot = ModEntityData.get(entity, ModAttachments.ORIGINAL_SLOT);
        if (targetSlot >= 0 && targetSlot < player.getInventory().getContainerSize()) {
            ItemStack entityStack = entity.getItem();
            if (entityStack.isEmpty()) return;

            int originalCount = entityStack.getCount();
            ItemStack remaining = insertIntoSlot(player.getInventory(), targetSlot, entityStack.copy());
            int moved = originalCount - remaining.getCount();

            if (moved > 0) {
                // 直接修改活引用的数量，不在 Pre 阶段调用 setItem（行为未定义）
                entityStack.shrink(moved);
                player.take(entity, moved);

                if (entityStack.isEmpty()) {
                    entity.discard();
                }

                // 已手动转移物品，拒绝本 tick 的默认拾取
                event.setCanPickup(TriState.FALSE);
            }
        }
    }

    /**
     * 玩家死亡瞬间：记录死亡坐标 & 拍摄背包快照（用于槽位还原）。
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 死亡坐标提示（在 Clone 事件给新玩家实例发送，避免死亡瞬间消息丢失）
        if (Config.COMMON.DEATH_COORDS_ENABLED.get()) {
            var lvl = player.level();
            GlobalPos deathGlobalPos = GlobalPos.of(lvl.dimension(), player.blockPosition());
            PENDING_DEATH_POS.put(player.getUUID(), deathGlobalPos);
            // 持久化到玩家附件，在死亡屏幕退出/关服后仍可恢复
            ModEntityData.put(player, ModAttachments.PLAYER_DEATH_POS, deathGlobalPos);
        }

        // 背包快照（死亡时始终生成）：
        // 1) restoreSlots 用于回填原槽位 2) death snapshot UI 需要保留真实槽位布局
        // LinkedHashMap 保证按槽位升序迭代，避免相同物品多栈时顺序不确定
        Map<Integer, ItemStack> snapshot = new LinkedHashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                snapshot.put(i, stack.copy());
            }
        }
        INVENTORY_SNAPSHOTS.put(player.getUUID(), snapshot);
    }

    /**
     * 死亡事件最终被其它模组取消时，清理 onPlayerDeath 中写入的临时状态。
     * <p>
     * onPlayerDeath 在 NORMAL 优先级运行，此处以 LOWEST 优先级并接受已取消的事件，
     * 从而能捕获所有低优先级的死亡取消动作，避免 PENDING_DEATH_POS / INVENTORY_SNAPSHOTS
     * 在玩家实际未死亡时永久驻留内存。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPlayerDeathCanceledCleanup(LivingDeathEvent event) {
        if (!event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        PENDING_DEATH_POS.remove(uuid);
        INVENTORY_SNAPSHOTS.remove(uuid);
    }

    /**
     * 掉落物生成时的核心处理：物品保留判定、属性标记、即时虚空恢复。
     */
    @SubscribeEvent
    public static void onPlayerDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Collection<ItemEntity> drops = event.getDrops();
        List<SavedItem> keptItems = new ArrayList<>();
        Iterator<ItemEntity> iterator = drops.iterator();

        // 获取快照
        Map<Integer, ItemStack> snapshot = INVENTORY_SNAPSHOTS.remove(player.getUUID());
        Map<Item, Deque<Map.Entry<Integer, ItemStack>>> snapshotBuckets = null;
        if (Config.COMMON.RESTORE_SLOTS_ENABLED.get() && snapshot != null && !snapshot.isEmpty()) {
            snapshotBuckets = new HashMap<>();
            for (var entry : snapshot.entrySet()) {
                snapshotBuckets.computeIfAbsent(entry.getValue().getItem(), ignored -> new ArrayDeque<>()).addLast(entry);
            }
        }
        captureDeathDropSnapshot(player, snapshot, drops);
        // 获取玩家历史安全点中的最佳候选（优先同维度且接近死亡点）
        GlobalPos lastSafePos = getBestHistoricalSafePos(player.getUUID(), player.level().dimension(), player.blockPosition());
        if (lastSafePos == null && ModEntityData.has(player, ModAttachments.SAFE_RECOVERY_POS)) {
            lastSafePos = ModEntityData.get(player, ModAttachments.SAFE_RECOVERY_POS);
        }

        ServerLevel serverLevel = player.level() instanceof ServerLevel level ? level : null;
        boolean immediateVoidRecovery = serverLevel != null
            && Config.COMMON.VOID_RECOVERY_ENABLED.get()
            && shouldImmediateVoidRecover(serverLevel, player.getY());

        // 缓存即时虚空恢复目标：同一死亡事件中所有物品共用同一恢复位置，
        // 避免为每个掉落物重复执行昂贵的 3D 安全点搜索（resolveRecoveryTarget）
        BlockPos cachedImmediateRecoveryPos = null;
        String cachedImmediateRecoverySource = null;

        while (iterator.hasNext()) {
            ItemEntity entity = iterator.next();
            ItemStack stack = entity.getItem();
            int matchedSlot = -1;

            if (snapshotBuckets != null) {
                Deque<Map.Entry<Integer, ItemStack>> candidateSlots = snapshotBuckets.get(stack.getItem());
                if (candidateSlots != null && !candidateSlots.isEmpty()) {
                    Iterator<Map.Entry<Integer, ItemStack>> candidateIt = candidateSlots.iterator();
                    while (candidateIt.hasNext()) {
                        var entry = candidateIt.next();
                        if (ItemStack.isSameItemSameComponents(entry.getValue(), stack)) {
                            matchedSlot = entry.getKey();
                            candidateIt.remove();
                            if (candidateSlots.isEmpty()) {
                                snapshotBuckets.remove(stack.getItem());
                            }
                            ModEntityData.put(entity, ModAttachments.ORIGINAL_SLOT, matchedSlot);
                            break;
                        }
                    }
                }
            }

            // 标记掉落物归属，供私有高亮使用
            UUID ownerId = player.getUUID();
            ModEntityData.put(entity, ModAttachments.OWNER_UUID, ownerId);
            OWNER_SCOREBOARD_NAMES.put(ownerId, player.getScoreboardName());
            ModEntityData.put(entity, ModAttachments.IS_DEATH_DROP, true);
            OWNED_DEATH_DROP_IDS.computeIfAbsent(ownerId, ignored -> ConcurrentHashMap.newKeySet()).add(entity.getId());
            ENTITY_DIMENSIONS.put(entity.getId(), player.level().dimension());

            // --- A. 物品保留 ---
            int amountToKeep = PreserveItems.howManyToPreserve(player, stack);
            if (amountToKeep > 0) {
                if (amountToKeep >= stack.getCount()) {
                    keptItems.add(new SavedItem(stack.copy(), matchedSlot));
                    iterator.remove();
                    continue;
                } else {
                    ItemStack kept = stack.split(amountToKeep);
                    keptItems.add(new SavedItem(kept, matchedSlot));
                    entity.setItem(stack);
                }
            }

            // --- B. 掉落物处理 (如果不保留) ---

            // 1. 发光改为“仅归属玩家可见”的定向私有高亮（在 PlayerTick 中处理）

            // 2. 物品韧性：使掉落物免疫所有伤害
            if (Config.COMMON.ITEM_RESILIENCE_ENABLED.get()) {
                entity.setInvulnerable(true);
            }

            // 3. 延长寿命
            if (Config.COMMON.EXTENDED_LIFETIME_ENABLED.get()) {
                if (Config.COMMON.DEATH_DROP_ITEMS_NEVER_DESPAWN.get()) {
                    entity.setUnlimitedLifetime();
                    entity.addTag("LENIENT_DEATH_INFINITE_LIFETIME");
                } else {
                    int lifetimeSeconds = Config.COMMON.DEATH_DROP_ITEM_LIFETIME_SECONDS.get();
                    entity.lifespan = lifetimeSeconds * 20;
                }
            }

            // 4. 写入安全位置数据 (用于防虚空)
            // 即使现在没掉进虚空，也要把这个“回家坐标”写在物品身上，万一它以后掉下去了呢
            if (lastSafePos != null) {
                ModEntityData.put(entity, ModAttachments.SAFE_RECOVERY_POS, lastSafePos);
            }

            if (immediateVoidRecovery && serverLevel != null) {
                if (cachedImmediateRecoveryPos == null) {
                    RecoveryTarget rt = resolveRecoveryTarget(serverLevel, entity, true);
                    cachedImmediateRecoveryPos = rt.pos();
                    cachedImmediateRecoverySource = rt.source();
                }
                double fromX = entity.getX();
                double fromY = entity.getY();
                double fromZ = entity.getZ();
                teleportItemToSafety(entity, cachedImmediateRecoveryPos);
                ModEntityData.put(entity, ModAttachments.VOID_RECOVERED, entity.tickCount);

                if (isVoidRecoveryDebugEnabled()) {
                    LOGGER.info("[LenientDeath][Recovery] Recover item {} mode={} trigger=death_drop_immediate_void source={} from ({}, {}, {}) -> ({}, {}, {})",
                            entity.getId(), Config.COMMON.VOID_RECOVERY_MODE.get(), cachedImmediateRecoverySource,
                            fromX, fromY, fromZ,
                            cachedImmediateRecoveryPos.getX() + 0.5, cachedImmediateRecoveryPos.getY(), cachedImmediateRecoveryPos.getZ() + 0.5);
                }
            }

            // ORIGINAL_SLOT 已在进入循环时统一匹配并写入
        }

        // 保存保留的物品
        if (!keptItems.isEmpty()) {
            SAVED_ITEMS.put(player.getUUID(), keptItems);
            // 持久化到玩家附件，在死亡屏幕退出/关服后仍可恢复
            List<ModAttachments.SavedItemEntry> entries = new ArrayList<>(keptItems.size());
            for (SavedItem s : keptItems) {
                entries.add(new ModAttachments.SavedItemEntry(s.stack().copy(), s.originalSlot()));
            }
            ModEntityData.put(player, ModAttachments.SAVED_ITEMS_DATA, entries);
        }
    }

    /**
     * 掉落事件被其它模组取消时，仍需清理死亡瞬间记录的背包快照，避免残留占用内存。
     */
    @SubscribeEvent(receiveCanceled = true)
    public static void onPlayerDropsCanceledCleanup(LivingDropsEvent event) {
        if (!event.isCanceled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            INVENTORY_SNAPSHOTS.remove(player.getUUID());
        }
    }

    /**
     * 服务器 tick 处理：仅在 DEATH_DROPS_ONLY 模式下，遍历所有已追踪的死亡掉落实体并执行
     * 虚空/危险恢复。相比全局 EntityTickEvent，此方式只迭代数十个死亡掉落物，而非
     * 服务器上可能存在的数千/万个 ItemEntity，大幅降低主线程 CPU 开销。
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onServerTick(ServerTickEvent.Post event) {
        boolean voidRecoveryEnabled = cachedVoidRecoveryEnabled;
        boolean hazardRecoveryEnabled = cachedHazardRecoveryEnabled;
        if (!voidRecoveryEnabled && !hazardRecoveryEnabled) return;

        // 此处仅处理 DEATH_DROPS_ONLY 模式；ALL_DROPS 模式由 onEntityTick 负责
        if (cachedVoidRecoveryMode != Config.VoidRecoveryMode.DEATH_DROPS_ONLY) return;

        if (ENTITY_DIMENSIONS.isEmpty()) return;

        MinecraftServer server = event.getServer();
        // Snapshot keySet to avoid ConcurrentModificationException if untrackOwnedDropEntity
        // is called during iteration (e.g. from onEntityLeaveLevel callbacks fired mid-tick).
        Integer[] entityIds = ENTITY_DIMENSIONS.keySet().toArray(Integer[]::new);
        for (Integer entityId : entityIds) {
            ResourceKey<Level> dimension = ENTITY_DIMENSIONS.get(entityId);
            if (dimension == null) continue;
            ServerLevel level = server.getLevel(dimension);
            if (level == null) continue;

            Entity maybeEntity = level.getEntity(entityId);
            if (!(maybeEntity instanceof ItemEntity item)) continue;
            // 在追踪的 ItemEntity 上执行恢复检查（无需额外的 DEATH_DROPS_ONLY 过滤，
            // ENTITY_DIMENSIONS 中仅存储死亡掉落物）
            processItemRecovery(item, level, voidRecoveryEnabled, hazardRecoveryEnabled);
        }
    }

    /**
     * 实体 tick 前处理：仅在 ALL_DROPS 模式下对 ItemEntity 执行虚空/危险恢复。
     * <p>
     * DEATH_DROPS_ONLY 模式（默认）下此方法立即返回，恢复逻辑由
     * {@link #onServerTick} 负责，以避免对全服所有掉落物进行每 tick 扫描。
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onEntityTick(EntityTickEvent.Pre event) {
        // DEATH_DROPS_ONLY 模式由 onServerTick 处理，此处退出以节省全局实体 tick 开销
        if (cachedVoidRecoveryMode == Config.VoidRecoveryMode.DEATH_DROPS_ONLY) return;

        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (item.level().isClientSide) return;

        boolean voidRecoveryEnabled = cachedVoidRecoveryEnabled;
        boolean hazardRecoveryEnabled = cachedHazardRecoveryEnabled;
        if (!voidRecoveryEnabled && !hazardRecoveryEnabled) return;

        if (!(item.level() instanceof ServerLevel serverLevel)) return;
        processItemRecovery(item, serverLevel, voidRecoveryEnabled, hazardRecoveryEnabled);
    }

    /**
     * 对单个 ItemEntity 执行虚空/危险恢复检查，由 onServerTick 和 onEntityTick 共享调用。
     */
    @SuppressWarnings("ConstantConditions")
    private static void processItemRecovery(ItemEntity item, ServerLevel serverLevel,
                                            boolean voidRecoveryEnabled, boolean hazardRecoveryEnabled) {
        var lvl = item.level();
        String recoveryReason = null;

        // ── Step 1: Cheap condition checks (no attachment lookups) ───────────────────────────
        if (voidRecoveryEnabled) {
            double triggerY = getVoidTriggerY(lvl.getMinBuildHeight());
            double currentY = item.getY();
            double predictedNextY = currentY + item.getDeltaMovement().y;
            if (currentY <= triggerY || predictedNextY <= triggerY) {
                recoveryReason = "void";
            }
        }

        if (recoveryReason == null && hazardRecoveryEnabled) {
            if (item.isOnFire() || item.isInLava()) {
                recoveryReason = item.isInLava() ? "lava" : "fire";
            }
        }

        if (recoveryReason == null) {
            if (isVoidRecoveryDebugEnabled() && voidRecoveryEnabled) {
                double triggerY = getVoidTriggerY(lvl.getMinBuildHeight());
                LOGGER.info("[LenientDeath][Recovery] Skip item {} reason=safe triggerY={} currentY={}",
                        item.getId(), triggerY, item.getY());
            }
            return;
        }

        // ── Step 2: Attachment lookups (only reached when item needs recovery) ─────────────
        Config.VoidRecoveryMode recoveryMode = cachedVoidRecoveryMode;

        // 检查是否刚刚恢复过（避免同一tick重复处理）
        if (ModEntityData.has(item, ModAttachments.VOID_RECOVERED)) {
            int recoveredAtTick = ModEntityData.get(item, ModAttachments.VOID_RECOVERED);
            if (recoveredAtTick >= 0 && item.tickCount - recoveredAtTick < 2) {
                return;
            }
        }

        // 限流检查
        if (!canRecoverFromVoidNow(item)) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.info("[LenientDeath][Recovery] Skip item {} reason=limiter_blocked at ({}, {}, {})",
                        item.getId(), item.getX(), item.getY(), item.getZ());
            }
            return;
        }

        double fromX = item.getX();
        double fromY = item.getY();
        double fromZ = item.getZ();

        RecoveryTarget recoveryTarget = resolveRecoveryTargetWithCache(serverLevel, item);
        teleportItemToSafety(item, recoveryTarget.pos());

        if ("lava".equals(recoveryReason) || "fire".equals(recoveryReason)) {
            item.clearFire();
        }

        ModEntityData.put(item, ModAttachments.VOID_RECOVERED, item.tickCount);

        if (isVoidRecoveryDebugEnabled()) {
            LOGGER.info("[LenientDeath][Recovery] Recover item {} mode={} trigger={} source={} from ({}, {}, {}) -> ({}, {}, {})",
                    item.getId(), recoveryMode, recoveryReason, recoveryTarget.source(),
                    fromX, fromY, fromZ,
                    recoveryTarget.pos().getX() + 0.5, recoveryTarget.pos().getY(), recoveryTarget.pos().getZ() + 0.5);
        }
    }

    /**
     * 实体离开世界时（消散/销毁），从追踪数据结构中清除对应的死亡掉落实体 ID。
     * <p>
     * 配合对 null 结果的保守处理策略（区块卸载时 getEntity 返回 null，
     * 但实体并未真正消失），此事件是检测实体永久消失的可靠来源。
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (event.getLevel().isClientSide()) return;
        untrackOwnedDropEntity(item);
    }

    /**
     * 解析恢复目标坐标，按优先级尝试：
     * <ol>
     *   <li>玩家历史安全点（最近、同维度）</li>
     *   <li>物品附件上的安全点（死亡时记录）</li>
     *   <li>物品当前位置附近的有3D距离最近安全点</li>
     *   <li>出生点附近 / 出生点回退</li>
     * </ol>
     */
    private static RecoveryTarget resolveRecoveryTarget(ServerLevel level, ItemEntity item, boolean allowExpensiveSearch) {
        BlockPos itemPos = item.blockPosition();

        // 策略1（最高优先级）：玩家历史安全点
        if (ModEntityData.has(item, ModAttachments.OWNER_UUID)) {
            UUID ownerId = ModEntityData.get(item, ModAttachments.OWNER_UUID);
            GlobalPos historical = getBestHistoricalSafePos(ownerId, level.dimension(), itemPos);
            if (historical != null) {
                BlockPos validated = validatePreferredSafePos(level, item, historical.pos());
                if (validated != null) {
                    return new RecoveryTarget(validated, "owner_history");
                }
            }
        }

        // 策略2：物品附件上的安全点（通常来自死亡时记录）
        GlobalPos safePos = ModEntityData.has(item, ModAttachments.SAFE_RECOVERY_POS)
                ? ModEntityData.get(item, ModAttachments.SAFE_RECOVERY_POS)
                : null;
        if (safePos != null && safePos.dimension() == level.dimension()) {
            BlockPos validated = validatePreferredSafePos(level, item, safePos.pos());
            if (validated != null) {
                return new RecoveryTarget(validated, "item_safe_pos");
            }
        }

        if (allowExpensiveSearch) {
            // 策略3：真正“最近”的安全位置（按三维距离）
            BlockPos nearest = findNearestSafeSpot(level, item, itemPos, 16, 20);
            if (nearest != null) {
                return new RecoveryTarget(nearest, "nearest_3d");
            }

            // 策略4：出生点附近
            BlockPos spawnPos = level.getSharedSpawnPos();
            BlockPos spawnNearest = findNearestSafeSpot(level, item, spawnPos, 8, 20);
            if (spawnNearest != null) {
                return new RecoveryTarget(spawnNearest, "spawn_nearest");
            }
        }

        BlockPos spawnPos = level.getSharedSpawnPos();
        if (!allowExpensiveSearch) {
            return new RecoveryTarget(new BlockPos(spawnPos.getX(), Math.max(level.getMinBuildHeight() + 1, level.getSeaLevel()), spawnPos.getZ()), "search_budget_fallback");
        }

        int fallbackY = Math.max(level.getMinBuildHeight() + 1, level.getSeaLevel());
        return new RecoveryTarget(new BlockPos(spawnPos.getX(), fallbackY, spawnPos.getZ()), "spawn_fallback");
    }

    /**
     * 带同 tick 缓存的恢复目标解析：同位置、同归属/安全点的物品共享搜索结果。
     */
    private static RecoveryTarget resolveRecoveryTargetWithCache(ServerLevel level, ItemEntity item) {
        UUID ownerId = ModEntityData.has(item, ModAttachments.OWNER_UUID)
                ? ModEntityData.get(item, ModAttachments.OWNER_UUID)
                : null;
        GlobalPos safePos = ModEntityData.has(item, ModAttachments.SAFE_RECOVERY_POS)
                ? ModEntityData.get(item, ModAttachments.SAFE_RECOVERY_POS)
                : null;

        BlockPos itemPos = item.blockPosition();
        int bucketX = Math.floorDiv(itemPos.getX(), RECOVERY_CACHE_POSITION_GRANULARITY);
        int bucketY = Math.floorDiv(itemPos.getY(), RECOVERY_CACHE_POSITION_GRANULARITY);
        int bucketZ = Math.floorDiv(itemPos.getZ(), RECOVERY_CACHE_POSITION_GRANULARITY);

        RecoveryTargetCacheKey key = new RecoveryTargetCacheKey(
                level.dimension(),
            bucketX,
            bucketY,
            bucketZ,
                ownerId,
                safePos
        );

        long gameTime = level.getGameTime();
        RecoveryTargetCacheValue cached = RECOVERY_TARGET_CACHE.get(key);
        if (cached != null && cached.gameTime == gameTime) {
            return cached.target;
        }

        if (recoverySearchBudgetTick != gameTime) {
            recoverySearchBudgetTick = gameTime;
            expensiveRecoverySearchesThisTick.set(0);
        }
        boolean allowExpensiveSearch = expensiveRecoverySearchesThisTick.getAndIncrement() < MAX_EXPENSIVE_RECOVERY_SEARCHES_PER_TICK;

        RecoveryTarget resolved = resolveRecoveryTarget(level, item, allowExpensiveSearch);
        RECOVERY_TARGET_CACHE.put(key, new RecoveryTargetCacheValue(gameTime, resolved));

        return resolved;
    }

    /**
     * 验证首选安全点是否可用，若不可用则在附近 3 格内微调。
     *
     * @return 验证后的可用位置，均不可用则返回 null
     */
    private static BlockPos validatePreferredSafePos(ServerLevel level, ItemEntity item, BlockPos preferredPos) {
        if (isValidRecoverySpot(level, item, preferredPos)) {
            return preferredPos;
        }

        // 安全点附近微调，避免目标点刚好被临时方块占用。
        // 使用单个 MutableBlockPos 避免每个候选位置分配新 BlockPos 对象（GC 压力）。
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    candidate.set(preferredPos.getX() + dx, preferredPos.getY(), preferredPos.getZ() + dz);
                    if (isValidRecoverySpot(level, item, candidate)) {
                        return candidate.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在以 center 为中心的 3D 范围内搜索距离最近的安全位置。
     * <p>
     * 采用由中心向外扩展的环形搜索策略：每次仅检查当前半径 r 的外环方块，
     * 当已找到安全点且其距离的平方 &lt; (r+1)² 时提前终止，跳过更远的搜索。
     * 与暴力遍历相比，在附近存在安全点时可大幅减少方块检查次数。
     *
     * @param horizontalRadius 水平搜索半径
     * @param verticalRange    垂直搜索范围（上下各此值）
     * @return 最近安全位置，找不到则返回 null
     */
    private static BlockPos findNearestSafeSpot(ServerLevel level, ItemEntity item, BlockPos center, int horizontalRadius, int verticalRange) {
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int checks = 0;

        int minY = Math.max(level.getMinBuildHeight() + 1, center.getY() - verticalRange);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, center.getY() + verticalRange);
        int centerY = Math.max(minY, Math.min(maxY, center.getY()));

        // 使用 MutableBlockPos 避免在多重循环中大量创建 BlockPos 对象
        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

        // 由中心向外扩展搜索，每次只检查半径为 r 的外环
        for (int r = 0; r <= horizontalRadius; r++) {
            // 提前终止：当前最优距离已小于下一环的最小可能距离（水平分量），
            // 任何水平距离 >= r 的候选点不可能比当前最优更近
            if (best != null && bestDistanceSq < (double) r * r) {
                break;
            }

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只检查半径 r 的外环边界（内部已在更小半径时检查过）
                    if (r > 0 && Math.abs(dx) < r && Math.abs(dz) < r) continue;

                    int horizontalDistanceSq = dx * dx + dz * dz;
                    if (best != null && horizontalDistanceSq >= bestDistanceSq) {
                        continue;
                    }

                    int maxVerticalDistance = verticalRange;
                    if (best != null) {
                        double remaining = bestDistanceSq - horizontalDistanceSq;
                        if (remaining < 0) {
                            continue;
                        }
                        maxVerticalDistance = Math.min(maxVerticalDistance, (int) Math.floor(Math.sqrt(remaining)));
                    }

                    for (int dy = 0; dy <= maxVerticalDistance; dy++) {
                        int yUp = centerY + dy;
                        if (yUp <= maxY) {
                            checks++;
                            if (checks > MAX_SAFE_SPOT_CHECKS_PER_SEARCH) {
                                return best;
                            }
                            candidate.set(center.getX() + dx, yUp, center.getZ() + dz);
                            if (isValidRecoverySpot(level, item, candidate)) {
                                double distanceSq = candidate.distSqr(center);
                                if (distanceSq < bestDistanceSq) {
                                    bestDistanceSq = distanceSq;
                                    best = candidate.immutable();
                                }
                            }
                        }

                        if (dy == 0) {
                            continue;
                        }

                        int yDown = centerY - dy;
                        if (yDown >= minY) {
                            checks++;
                            if (checks > MAX_SAFE_SPOT_CHECKS_PER_SEARCH) {
                                return best;
                            }
                            candidate.set(center.getX() + dx, yDown, center.getZ() + dz);
                            if (isValidRecoverySpot(level, item, candidate)) {
                                double distanceSq = candidate.distSqr(center);
                                if (distanceSq < bestDistanceSq) {
                                    bestDistanceSq = distanceSq;
                                    best = candidate.immutable();
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * 检查给定位置是否为有效的恢复落点。
     * <p>
     * 要求：实心地板 + 空气脚/头 + 无流体 + 无碰撞体。
     *
     * @param feetPos 物品将被放置的位置（地板上方的空气方块）
     */
    private static boolean isValidRecoverySpot(ServerLevel level, ItemEntity item, BlockPos feetPos) {
        // Guard: only query block states for loaded chunks to prevent synchronous chunk loading
        // on the main thread. All three positions (floor/feet/head) share the same XZ chunk,
        // so a single isLoaded check on feetPos is sufficient.
        if (!level.isLoaded(feetPos)) {
            return false;
        }

        // Use local MutableBlockPos instead of shared statics for thread safety (Folia compatibility)
        // and to eliminate re-entrancy risk if getBlockState ever triggers callbacks.
        BlockPos.MutableBlockPos floorPos = new BlockPos.MutableBlockPos(feetPos.getX(), feetPos.getY() - 1, feetPos.getZ());
        BlockPos.MutableBlockPos headPos = new BlockPos.MutableBlockPos(feetPos.getX(), feetPos.getY() + 1, feetPos.getZ());

        var floor = level.getBlockState(floorPos);
        var feet = level.getBlockState(feetPos);
        var head = level.getBlockState(headPos);

        if (!floor.isSolidRender(level, floorPos)) {
            return false;
        }

        if (!feet.isAir() || !head.isAir()) {
            return false;
        }

        if (level.getFluidState(floorPos).isSource() || level.getFluidState(feetPos).isSource() || level.getFluidState(headPos).isSource()) {
            return false;
        }

        // feet and head are confirmed AIR — air blocks have empty VoxelShapes, so noCollision
        // would always return true here. Skipping the call avoids allocating a new AABB object
        // and performing expensive VoxelShape computation on every candidate position.
        return true;
    }

    /**
     * 获取玩家站立时的安全位置。
     * <p>
     * 由于调用时玩家已确认 {@code onGround()}，直接返回当前块位置。
     * 不使用 Heightmap，避免返回世界最高表面而忽略中间平台。
     */
    private static BlockPos resolveStandingSafePos(BlockPos playerPos) {
        return playerPos;
    }

    /**
     * 将安全位置压入玩家的历史队列（去重 + 限长）。
     */
    private static void pushSafePosHistory(UUID playerId, GlobalPos pos) {
        Deque<GlobalPos> history = SAFE_POS_HISTORY.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        GlobalPos latest = history.peekFirst();
        if (latest != null && latest.dimension().equals(pos.dimension()) && latest.pos().equals(pos.pos())) {
            return;
        }

        history.addFirst(pos);
        while (history.size() > SAFE_POS_HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    /**
     * 从玩家历史安全点中查找同维度且距离最近的记录。
     *
     * @param dimension 目标维度
     * @param nearPos   参考位置（通常为物品当前坐标）
     * @return 最近的历史安全点，找不到则返回 null
     */
    private static GlobalPos getBestHistoricalSafePos(UUID playerId, ResourceKey<Level> dimension, BlockPos nearPos) {
        Deque<GlobalPos> history = SAFE_POS_HISTORY.get(playerId);
        if (history == null || history.isEmpty()) {
            return null;
        }

        GlobalPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (GlobalPos candidate : history) {
            if (!candidate.dimension().equals(dimension)) {
                continue;
            }

            double distanceSq = candidate.pos().distSqr(nearPos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 安全传送物品到指定位置。
     * <p>
     * pos 是 feetPos（地板上方的空气方块），物品被放在该方块底部（地板表面）。
     * 重置速度、下落距离，并给予短暂拾取冷却让物品稳定落地。
     * <p>
     * 为避免多个物品精确重叠在同一坐标（可能引发视觉堆叠），
     * 根据实体 ID 给予微小的确定性水平散布速度（最大 ±0.09 格/tick），
     * 不影响落点安全性而可使物品自然分散到 1–2 格范围内。
     */
    private static void teleportItemToSafety(ItemEntity item, BlockPos pos) {
        double targetX = pos.getX() + 0.5;
        double targetY = pos.getY();
        double targetZ = pos.getZ() + 0.5;

        // Tiny deterministic spread based on entity ID so multiple items from the same death
        // event don't pile up at the exact same coordinate.  Prime multipliers (7, 11) and
        // offsets (13, 7) for x/z produce independent hash patterns; TELEPORT_SPREAD_BUCKETS
        // caps the modulo range; TELEPORT_SPREAD_SCALE converts bucket index to blocks/tick.
        int id = item.getId();
        double vx = ((id * 7 + 13) % TELEPORT_SPREAD_BUCKETS - TELEPORT_SPREAD_BUCKETS / 2) * TELEPORT_SPREAD_SCALE;
        double vz = ((id * 11 + 7) % TELEPORT_SPREAD_BUCKETS - TELEPORT_SPREAD_BUCKETS / 2) * TELEPORT_SPREAD_SCALE;

        item.setDeltaMovement(vx, TELEPORT_UPWARD_VELOCITY, vz);

        // setPos + hurtMarked 强制同步位置到客户端
        item.setPos(targetX, targetY, targetZ);
        item.hurtMarked = true;

        item.setNoGravity(false);
        item.setPickUpDelay(20);  // 拾取冷却 1 秒
        item.fallDistance = 0.0f; // 重置下落距离
    }

    /**
     * 限流检查：在时间窗口内限制同一物品的恢复次数，防止循环触发。
     *
     * @return 是否允许本次恢复
     */
    private static boolean canRecoverFromVoidNow(ItemEntity item) {
        int now = item.tickCount;
        int windowTicks = Config.COMMON.VOID_RECOVERY_WINDOW_TICKS.get();
        int maxRecoveries = Config.COMMON.VOID_RECOVERY_MAX_RECOVERIES.get();
        int cooldownTicks = Config.COMMON.VOID_RECOVERY_COOLDOWN_TICKS.get();

        int cooldownUntil = ModEntityData.has(item, ModAttachments.VOID_RECOVERY_COOLDOWN_UNTIL_TICK)
                ? ModEntityData.get(item, ModAttachments.VOID_RECOVERY_COOLDOWN_UNTIL_TICK)
                : -1;

        if (cooldownUntil > now) {
            return false;
        }

        int windowStart = ModEntityData.has(item, ModAttachments.VOID_RECOVERY_WINDOW_START_TICK)
                ? ModEntityData.get(item, ModAttachments.VOID_RECOVERY_WINDOW_START_TICK)
                : -1;
        int countInWindow = ModEntityData.has(item, ModAttachments.VOID_RECOVERY_COUNT_IN_WINDOW)
                ? ModEntityData.get(item, ModAttachments.VOID_RECOVERY_COUNT_IN_WINDOW)
                : 0;

        if (windowStart < 0 || now - windowStart >= windowTicks) {
            windowStart = now;
            countInWindow = 0;
        }

        countInWindow++;

        ModEntityData.put(item, ModAttachments.VOID_RECOVERY_WINDOW_START_TICK, windowStart);

        if (countInWindow >= maxRecoveries) {
            ModEntityData.put(item, ModAttachments.VOID_RECOVERY_COUNT_IN_WINDOW, 0);
            ModEntityData.put(item, ModAttachments.VOID_RECOVERY_WINDOW_START_TICK, now);
            ModEntityData.put(item, ModAttachments.VOID_RECOVERY_COOLDOWN_UNTIL_TICK, now + cooldownTicks);
        } else {
            ModEntityData.put(item, ModAttachments.VOID_RECOVERY_COUNT_IN_WINDOW, countInWindow);
            ModEntityData.put(item, ModAttachments.VOID_RECOVERY_COOLDOWN_UNTIL_TICK, -1);
        }

        return true;
    }

    private static double getVoidTriggerY(int minBuildHeight) {
        return minBuildHeight - VOID_RECOVERY_TRIGGER_OFFSET;
    }

    private static boolean shouldImmediateVoidRecover(ServerLevel level, double playerY) {
        return playerY <= level.getMinBuildHeight() + IMMEDIATE_VOID_RECOVERY_Y_MARGIN;
    }

    /**
     * 玩家重生时：发送死亡坐标、还原保留物品、继承安全位置。
     * <p>
     * 内存 Map 优先（正常重生路径），Attachment 回退（死亡屏幕退出/关服后重连）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        ServerPlayer newPlayer = (ServerPlayer) event.getEntity();
        UUID uuid = event.getOriginal().getUUID();

        // 重生后客户端会收到服务端重新同步的原始实体数据（不含发光标志），
        // 必须清空旧的高亮跟踪记录，下次 refreshPrivateHighlights 会为所有掉落物重新发送发光包。
        PRIVATE_HIGHLIGHT_COLORS.remove(uuid);
        GLOW_TEAMS_INITIALIZED.remove(uuid);

        // 清理死亡瞬间记录的背包快照（keepInventory=true 或其他模组抑制了 LivingDropsEvent
        // 导致 onPlayerDrops 未被调用时，快照可能残留）
        INVENTORY_SNAPSHOTS.remove(uuid);

        // ── 死亡坐标消息 ─────────────────────────────────────
        // 优先从内存 Map 读取（正常重生），回退到 Attachment（死亡屏幕断连后重连）
        GlobalPos deathPos = PENDING_DEATH_POS.remove(uuid);
        if (deathPos == null && ModEntityData.has(event.getOriginal(), ModAttachments.PLAYER_DEATH_POS)) {
            deathPos = ModEntityData.get(event.getOriginal(), ModAttachments.PLAYER_DEATH_POS);
        }
        if (deathPos != null && Config.COMMON.DEATH_COORDS_ENABLED.get()) {
            newPlayer.sendSystemMessage(Component.translatable(
                "lenientdeath.death_message",
                deathPos.pos().getX(),
                deathPos.pos().getY(),
                deathPos.pos().getZ(),
                deathPos.dimension().location().toString()
            ).withStyle(ChatFormatting.YELLOW));
        }

        // ── 恢复保留物品 ─────────────────────────────────────
        // 优先从内存 Map 读取（正常重生），回退到 Attachment（死亡屏幕断连后重连）
        List<SavedItem> items = SAVED_ITEMS.remove(uuid);
        if (items == null && ModEntityData.has(event.getOriginal(), ModAttachments.SAVED_ITEMS_DATA)) {
            List<ModAttachments.SavedItemEntry> persisted = ModEntityData.get(event.getOriginal(), ModAttachments.SAVED_ITEMS_DATA);
            if (persisted != null && !persisted.isEmpty()) {
                items = new ArrayList<>(persisted.size());
                for (ModAttachments.SavedItemEntry entry : persisted) {
                    items.add(new SavedItem(entry.stack(), entry.slot()));
                }
            }
        }
        if (items != null) {
            boolean restoreToSlot = Config.COMMON.RESTORE_SLOTS_ENABLED.get();

            for (SavedItem saved : items) {
                ItemStack stack = saved.stack().copy();

                if (restoreToSlot && saved.originalSlot() >= 0 && saved.originalSlot() < newPlayer.getInventory().getContainerSize()) {
                    stack = insertIntoSlot(newPlayer.getInventory(), saved.originalSlot(), stack);
                }

                if (!stack.isEmpty() && !newPlayer.getInventory().add(stack)) {
                    newPlayer.drop(stack, true, false);
                }
            }
        }

        // 重要：在重生时，把旧玩家的“安全位置”继承给新玩家
        // 这样如果玩家刚复活又掉虚空了，还能救回来
        GlobalPos oldSafePos = ModEntityData.has(event.getOriginal(), ModAttachments.SAFE_RECOVERY_POS)
            ? ModEntityData.get(event.getOriginal(), ModAttachments.SAFE_RECOVERY_POS)
            : null;
        if (oldSafePos != null) {
            ModEntityData.put(newPlayer, ModAttachments.SAFE_RECOVERY_POS, oldSafePos);
            pushSafePosHistory(newPlayer.getUUID(), oldSafePos);
        }
    }

    /**
     * 玩家登出时清理所有运行时状态，避免内存泄漏。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            clearPrivateHighlights(serverPlayer);
        }
        SAVED_ITEMS.remove(uuid);
        INVENTORY_SNAPSHOTS.remove(uuid);
        SAFE_POS_HISTORY.remove(uuid);
        PENDING_DEATH_POS.remove(uuid);
        PRIVATE_HIGHLIGHT_COLORS.remove(uuid);
        DEATH_DROP_SNAPSHOTS.remove(uuid);
        NEXT_DEATH_DROP_SNAPSHOT_ID.remove(uuid);
        PENDING_SNAPSHOT_RESTORES.remove(uuid);
        // Intentionally NOT clearing OWNED_DEATH_DROP_IDS, ENTITY_DIMENSIONS, or
        // OWNER_SCOREBOARD_NAMES on logout: the death-drop item entities still exist in the
        // world while the player is offline, so these maps must survive the session gap.
        // When the player reconnects, refreshPrivateHighlights / cleanupStaleOwnedDropIds will
        // prune any IDs whose items have despawned since then.  onServerStopped clears all
        // maps when the world is unloaded, so there is no long-term leak.
        GLOW_TEAMS_INITIALIZED.remove(uuid);
    }

    /**
     * 玩家登录时处理离线计划恢复队列。
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        OWNER_SCOREBOARD_NAMES.put(player.getUUID(), player.getScoreboardName());

        Deque<Integer> queued = PENDING_SNAPSHOT_RESTORES.remove(player.getUUID());
        if (queued == null || queued.isEmpty()) {
            return;
        }

        int success = 0;
        int failed = 0;
        for (Integer snapshotId : queued) {
            int restored = restoreDeathDropSnapshot(player, snapshotId);
            if (restored >= 0) {
                success++;
            } else {
                failed++;
            }
        }

        if (success > 0) {
            player.sendSystemMessage(Component.translatable(
                    "lenientdeath.command.snapshot.restore.scheduled.executed",
                    success
            ).withStyle(ChatFormatting.GREEN));
        }
        if (failed > 0) {
            player.sendSystemMessage(Component.translatable(
                    "lenientdeath.command.snapshot.restore.scheduled.failed",
                    failed
            ).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * 服务器停止时清理全部运行时状态。
     * <p>
     * 主要用于单机环境下切换世界，避免静态缓存跨世界残留。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SAVED_ITEMS.clear();
        INVENTORY_SNAPSHOTS.clear();
        SAFE_POS_HISTORY.clear();
        PENDING_DEATH_POS.clear();
        PRIVATE_HIGHLIGHT_COLORS.clear();
        DEATH_DROP_SNAPSHOTS.clear();
        NEXT_DEATH_DROP_SNAPSHOT_ID.clear();
        PENDING_SNAPSHOT_RESTORES.clear();
        OWNED_DEATH_DROP_IDS.clear();
        OWNER_SCOREBOARD_NAMES.clear();
        GLOW_TEAMS_INITIALIZED.clear();
        RECOVERY_TARGET_CACHE.clear();
        ENTITY_DIMENSIONS.clear();
        recoverySearchBudgetTick = Long.MIN_VALUE;
        expensiveRecoverySearchesThisTick.set(0);
    }

    private static void captureDeathDropSnapshot(ServerPlayer player,
                                                 Map<Integer, ItemStack> inventorySnapshot,
                                                 Collection<ItemEntity> drops) {
        Map<Integer, ItemStack> slotItems = new LinkedHashMap<>();
        int containerSize = player.getInventory().getContainerSize();

        // 优先使用死亡瞬间背包槽位快照，确保 UI 能按原槽位还原
        if (inventorySnapshot != null && !inventorySnapshot.isEmpty()) {
            for (var entry : inventorySnapshot.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    slotItems.put(entry.getKey(), entry.getValue().copy());
                }
            }
        } else {
            // 回退：极少数情况下若无背包快照，则按掉落顺序写入临时槽位
            int fallbackSlot = 0;
            for (ItemEntity entity : drops) {
                ItemStack stack = entity.getItem();
                if (!stack.isEmpty()) {
                    slotItems.put(fallbackSlot++, stack.copy());
                }
            }
            containerSize = Math.max(containerSize, slotItems.size());
        }

        if (slotItems.isEmpty()) {
            return;
        }

        UUID playerId = player.getUUID();
        int snapshotId = NEXT_DEATH_DROP_SNAPSHOT_ID.compute(playerId, (ignored, old) -> old == null ? 1 : old + 1);
        DeathDropSnapshot snapshot = new DeathDropSnapshot(
                snapshotId,
                player.getGameProfile().getName(),
                player.level().dimension(),
                player.blockPosition().immutable(),
                player.level().getGameTime(),
                containerSize,
                slotItems
        );

        Deque<DeathDropSnapshot> deque = DEATH_DROP_SNAPSHOTS.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        deque.addFirst(snapshot);

        int maxSnapshots = Math.max(1, Config.COMMON.DEATH_DROP_SNAPSHOT_MAX_PER_PLAYER.get());
        while (deque.size() > maxSnapshots) {
            deque.removeLast();
        }
    }

    private static DeathDropSnapshotSummary toSummary(DeathDropSnapshot snapshot) {
        return new DeathDropSnapshotSummary(
                snapshot.id(),
                snapshot.playerName(),
                snapshot.dimension(),
                snapshot.deathPos(),
                snapshot.slotItems().size(),
                snapshot.gameTime()
        );
    }

    public static List<DeathDropSnapshotPlayerSummary> getDeathDropSnapshotPlayerSummaries() {
        if (DEATH_DROP_SNAPSHOTS.isEmpty()) {
            return List.of();
        }

        List<DeathDropSnapshotPlayerSummary> result = new ArrayList<>(DEATH_DROP_SNAPSHOTS.size());
        for (var entry : DEATH_DROP_SNAPSHOTS.entrySet()) {
            UUID playerId = entry.getKey();
            Deque<DeathDropSnapshot> deque = entry.getValue();
            if (deque == null || deque.isEmpty()) {
                continue;
            }
            DeathDropSnapshot latest = deque.peekFirst();
            result.add(new DeathDropSnapshotPlayerSummary(playerId, latest.playerName(), deque.size()));
        }
        result.sort((a, b) -> a.playerName().compareToIgnoreCase(b.playerName()));
        return result;
    }

    public static List<DeathDropSnapshotSummary> getDeathDropSnapshotSummaries(UUID playerId) {
        Deque<DeathDropSnapshot> snapshots = DEATH_DROP_SNAPSHOTS.get(playerId);
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }

        List<DeathDropSnapshotSummary> summaries = new ArrayList<>(snapshots.size());
        for (DeathDropSnapshot snapshot : snapshots) {
            summaries.add(toSummary(snapshot));
        }
        return summaries;
    }

    public static DeathDropSnapshotView getDeathDropSnapshot(UUID playerId, int snapshotId) {
        Deque<DeathDropSnapshot> snapshots = DEATH_DROP_SNAPSHOTS.get(playerId);
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }

        for (DeathDropSnapshot snapshot : snapshots) {
            if (snapshot.id() == snapshotId) {
                Map<Integer, ItemStack> copied = new LinkedHashMap<>();
                for (var entry : snapshot.slotItems().entrySet()) {
                    copied.put(entry.getKey(), entry.getValue().copy());
                }
                return new DeathDropSnapshotView(toSummary(snapshot), snapshot.containerSize(), copied);
            }
        }
        return null;
    }

    public static int restoreDeathDropSnapshot(ServerPlayer target, int snapshotId) {
        DeathDropSnapshotView view = getDeathDropSnapshot(target.getUUID(), snapshotId);
        if (view == null) {
            return -1;
        }

        int restoredStacks = 0;
        List<Map.Entry<Integer, ItemStack>> entries = new ArrayList<>(view.slotItems().entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (var entry : entries) {
            int slot = entry.getKey();
            ItemStack stack = entry.getValue();
            if (stack.isEmpty()) {
                continue;
            }
            restoredStacks++;
            ItemStack copy = stack.copy();

            if (slot >= 0 && slot < target.getInventory().getContainerSize()) {
                copy = insertIntoSlot(target.getInventory(), slot, copy);
            }

            if (!copy.isEmpty() && !target.getInventory().add(copy)) {
                target.drop(copy, true, false);
            }
        }
        return restoredStacks;
    }

    public static int restoreLatestDeathDropSnapshot(ServerPlayer target) {
        Deque<DeathDropSnapshot> snapshots = DEATH_DROP_SNAPSHOTS.get(target.getUUID());
        if (snapshots == null || snapshots.isEmpty()) {
            return -1;
        }
        return restoreDeathDropSnapshot(target, snapshots.peekFirst().id());
    }

    public static int clearDeathDropSnapshots(UUID playerId) {
        Deque<DeathDropSnapshot> removed = DEATH_DROP_SNAPSHOTS.remove(playerId);
        NEXT_DEATH_DROP_SNAPSHOT_ID.remove(playerId);
        return removed == null ? 0 : removed.size();
    }

    public static boolean scheduleDeathDropSnapshotRestore(UUID playerId, int snapshotId) {
        if (getDeathDropSnapshot(playerId, snapshotId) == null) {
            return false;
        }
        Deque<Integer> queue = PENDING_SNAPSHOT_RESTORES.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        // Deduplicate: do not queue the same snapshotId more than once.
        // Without this guard an admin (or a command block loop) could repeatedly enqueue the
        // same ID for an offline player, growing the deque without bound and eventually
        // causing an OOM on the server.
        // The O(n) contains() scan is acceptable here: the queue is bounded by
        // DEATH_DROP_SNAPSHOT_MAX_PER_PLAYER distinct snapshot IDs (typically ≤ 10),
        // and this method is only called from admin commands, not hot paths.
        if (!queue.contains(snapshotId)) {
            queue.addLast(snapshotId);
        }
        return true;
    }

    /**
     * 刷新指定玩家的私有高亮：扫描附近归属该玩家（及在当前可见性模式下
     * 其他玩家）的 ItemEntity，发送发光数据包和颜色队伍数据包。
     * <p>
     * 可见性由 {@link Config.GlowVisibility} 控制：
     * <ul>
     *   <li>DEAD_PLAYER — 只有掉落物的归属玩家自己能看到（原有行为）</li>
     *   <li>DEAD_PLAYER_AND_TEAM — 归属玩家及其队伍成员都能看到</li>
     *   <li>EVERYONE — 所有玩家都能看到所有死亡掉落物</li>
     * </ul>
     * <p>
     * 修复：DEAD_PLAYER 模式下仅扫描本玩家自己的 OWNED_DEATH_DROP_IDS；
     * DEAD_PLAYER_AND_TEAM / EVERYONE 模式下还会扫描其他玩家的
     * OWNED_DEATH_DROP_IDS，使对应玩家真正能看到高亮效果。
     * 陈旧实体的清理（ENTITY_DIMENSIONS 等）只在各自归属玩家的扫描中进行，
     * 避免跨玩家误删。
     */
    private static void refreshPrivateHighlights(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        double scanRadius = getPrivateHighlightRadius();
        int maxScannedEntities = getPrivateHighlightMaxScannedEntities();

        UUID playerId = player.getUUID();
        Map<Integer, HighlightEntry> previous = PRIVATE_HIGHLIGHT_COLORS.computeIfAbsent(playerId, ignored -> new HashMap<>());
        Map<Integer, HighlightEntry> current = new HashMap<>();

        // 确保该玩家已收到所有颜色队伍的创建包
        ensureGlowTeamsSent(player);

        Config.GlowVisibility visibility = Config.COMMON.GLOW_VISIBILITY.get();
        // 缓存 owner -> shouldShow 结果，避免同一归属者的多个掉落物重复查询队伍/离线缓存
        Map<UUID, Boolean> shouldShowCache = new HashMap<>();
        double scanRadiusSq = scanRadius * scanRadius;
        MinecraftServer server = player.getServer();

        int processed = 0;

        // ── 第一阶段：扫描本玩家自己的死亡掉落物（并清理陈旧 ID）────────────────────
        Set<Integer> ownTrackedIds = OWNED_DEATH_DROP_IDS.get(playerId);
        List<Integer> staleTrackedIds = new ArrayList<>();
        if (ownTrackedIds != null) {
            for (Integer entityId : ownTrackedIds) {
                if (processed >= maxScannedEntities) {
                    break;
                }

                // 跨维度处理：实体可能在与玩家不同的维度（例如玩家在主世界，物品在地狱）
                // 若 ENTITY_DIMENSIONS 中无记录（不应发生），视为在当前维度处理（兼容旧状态）
                ResourceKey<Level> entityDimension = ENTITY_DIMENSIONS.get(entityId);
                if (entityDimension != null && !entityDimension.equals(serverLevel.dimension())) {
                    // 实体在不同维度：若该维度已加载则验证实体是否仍然存在，否则跳过（不标记为失效）
                    ServerLevel entityLevel = server != null ? server.getLevel(entityDimension) : null;
                    if (entityLevel != null) {
                        Entity e = entityLevel.getEntity(entityId);
                        // null means entity might be in an unloaded chunk — skip conservatively.
                        // onEntityLeaveLevel handles actual despawns.
                        if (e != null && (!(e instanceof ItemEntity) || !e.isAlive())) {
                            staleTrackedIds.add(entityId);
                        }
                    }
                    // 不同维度的物品无需向当前维度的玩家发送高亮包
                    continue;
                }

                Entity maybeEntity = serverLevel.getEntity(entityId);
                if (maybeEntity == null) {
                    // getEntity() returns null for entities in unloaded chunks as well as
                    // truly-gone entities.  We cannot distinguish the two cases without
                    // knowing the entity's last position, so we skip conservatively here.
                    // Real despawns are handled by onEntityLeaveLevel, which calls
                    // untrackOwnedDropEntity and removes the ID from ENTITY_DIMENSIONS.
                    continue;
                }
                if (!(maybeEntity instanceof ItemEntity item) || !item.isAlive()) {
                    staleTrackedIds.add(entityId);
                    continue;
                }

                if (item.distanceToSqr(player) > scanRadiusSq) {
                    continue;
                }

                if (!ModEntityData.has(item, ModAttachments.OWNER_UUID)) {
                    staleTrackedIds.add(entityId);
                    continue;
                }

                processed++;
                UUID owner = ModEntityData.get(item, ModAttachments.OWNER_UUID);
                boolean shouldShow = shouldShowCache.computeIfAbsent(owner,
                        o -> shouldShowGlowTo(player, o, visibility, serverLevel));

                if (shouldShow) {
                    int visibleEntityId = item.getId();
                    ChatFormatting color = getGlowColorForItem(item);
                    current.put(visibleEntityId, new HighlightEntry(color, item.getStringUUID()));

                    HighlightEntry prevEntry = previous.get(visibleEntityId);
                    if (prevEntry == null) {
                        sendPrivateGlowPacket(player, item, true);
                        sendGlowColorPacket(player, item, color);
                    } else if (prevEntry.color() != color) {
                        removeGlowColorPacket(player, item, prevEntry.color());
                        sendGlowColorPacket(player, item, color);
                    }
                }
            }
        }

        // 清理自己的陈旧实体 ID
        if (ownTrackedIds != null && !staleTrackedIds.isEmpty()) {
            ownTrackedIds.removeAll(staleTrackedIds);
            for (Integer staleId : staleTrackedIds) {
                ENTITY_DIMENSIONS.remove(staleId);
            }
            if (ownTrackedIds.isEmpty()) {
                OWNED_DEATH_DROP_IDS.remove(playerId);
                OWNER_SCOREBOARD_NAMES.remove(playerId);
            }
        }

        // ── 第二阶段：当模式为 DEAD_PLAYER_AND_TEAM / EVERYONE 时，
        //              还需扫描其他玩家的死亡掉落物并判断当前玩家是否应看到 ────────────────
        // 修复：旧代码仅扫描 OWNED_DEATH_DROP_IDS.get(playerId)（本玩家自己的掉落物），
        // 导致 DEAD_PLAYER_AND_TEAM / EVERYONE 模式下非归属玩家永远看不到高亮。
        if (visibility != Config.GlowVisibility.DEAD_PLAYER && processed < maxScannedEntities) {
            for (Map.Entry<UUID, Set<Integer>> ownerEntry : OWNED_DEATH_DROP_IDS.entrySet()) {
                UUID ownerId = ownerEntry.getKey();
                if (ownerId.equals(playerId)) {
                    // 已在第一阶段处理
                    continue;
                }

                // 跳过本玩家不应看到的归属者（DEAD_PLAYER_AND_TEAM 检查队伍，EVERYONE 全通过）
                boolean shouldShow = shouldShowCache.computeIfAbsent(ownerId,
                        o -> shouldShowGlowTo(player, o, visibility, serverLevel));
                if (!shouldShow) {
                    continue;
                }

                Set<Integer> otherOwnedIds = ownerEntry.getValue();
                if (otherOwnedIds == null || otherOwnedIds.isEmpty()) {
                    continue;
                }

                for (Integer entityId : otherOwnedIds) {
                    if (processed >= maxScannedEntities) {
                        break;
                    }

                    ResourceKey<Level> entityDimension = ENTITY_DIMENSIONS.get(entityId);
                    if (entityDimension != null && !entityDimension.equals(serverLevel.dimension())) {
                        // 不同维度：跳过，不在此处清理陈旧 ID（由归属玩家自己的扫描负责）
                        continue;
                    }

                    Entity maybeEntity = serverLevel.getEntity(entityId);
                    if (!(maybeEntity instanceof ItemEntity item) || !item.isAlive()) {
                        // 不清理陈旧 ID，留给归属玩家的扫描处理
                        continue;
                    }

                    if (item.distanceToSqr(player) > scanRadiusSq) {
                        continue;
                    }

                    processed++;
                    int visibleEntityId = item.getId();
                    ChatFormatting color = getGlowColorForItem(item);
                    current.put(visibleEntityId, new HighlightEntry(color, item.getStringUUID()));

                    HighlightEntry prevEntry = previous.get(visibleEntityId);
                    if (prevEntry == null) {
                        sendPrivateGlowPacket(player, item, true);
                        sendGlowColorPacket(player, item, color);
                    } else if (prevEntry.color() != color) {
                        removeGlowColorPacket(player, item, prevEntry.color());
                        sendGlowColorPacket(player, item, color);
                    }
                }

                if (processed >= maxScannedEntities) {
                    break;
                }
            }
        }

        // ── 移除不再可见的旧高亮 ─────────────────────────────────────────────────────
        // 注意：即使实体已消失（自然消散），也必须发送队伍移除包，否则客户端队伍列表将无限积累废弃 UUID。
        for (var entry : previous.entrySet()) {
            int entityId = entry.getKey();
            if (!current.containsKey(entityId)) {
                HighlightEntry prev = entry.getValue();
                // 始终发送队伍移除包，防止客户端内存泄漏（即使实体已不存在）
                removeGlowColorPacket(player, prev.entityUuidString(), prev.color());
                // 实体仍存活时才发送关闭发光包
                Entity maybeEntity = serverLevel.getEntity(entityId);
                if (maybeEntity != null && maybeEntity.isAlive()) {
                    sendPrivateGlowPacket(player, maybeEntity, false);
                }
            }
        }

        PRIVATE_HIGHLIGHT_COLORS.put(playerId, current);
    }

    /**
     * 判断是否应该向指定玩家显示物品的发光高亮。
     */
    private static boolean shouldShowGlowTo(ServerPlayer viewer, UUID ownerId, Config.GlowVisibility visibility, ServerLevel level) {
        return switch (visibility) {
            case DEAD_PLAYER -> viewer.getUUID().equals(ownerId);
            case EVERYONE -> true;
            case DEAD_PLAYER_AND_TEAM -> {
                if (viewer.getUUID().equals(ownerId)) yield true;
                // 检查队伍
                PlayerTeam viewerTeam = viewer.getTeam() instanceof PlayerTeam pt ? pt : null;
                // 查找死亡玩家的队伍
                ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(ownerId);
                PlayerTeam ownerTeam = null;
                if (ownerPlayer != null) {
                    ownerTeam = ownerPlayer.getTeam() instanceof PlayerTeam pt ? pt : null;
                } else {
                    // 玩家离线时只使用运行期缓存名称，避免主线程潜在 I/O。
                    String ownerScoreboardName = OWNER_SCOREBOARD_NAMES.get(ownerId);
                    if (ownerScoreboardName != null && !ownerScoreboardName.isEmpty()) {
                        Scoreboard scoreboard = level.getScoreboard();
                        ownerTeam = scoreboard.getPlayersTeam(ownerScoreboardName);
                    }
                }
                if (ownerTeam == null && viewerTeam == null && Config.COMMON.NO_TEAM_IS_VALID_TEAM.get()) {
                    // 双方都无队伍，且 noTeamIsValidTeam 为 true
                    yield true;
                }
                yield ownerTeam != null && ownerTeam.equals(viewerTeam);
            }
        };
    }

    /**
     * 确保已向玩家发送颜色队伍的创建数据包。
     * 只在玩家首次进入高亮扫描时发送一次。
     */
    private static void ensureGlowTeamsSent(ServerPlayer player) {
        if (GLOW_TEAMS_INITIALIZED.add(player.getUUID())) {
            for (PlayerTeam team : GLOW_COLOR_TEAMS.values()) {
                player.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            }
        }
    }

    /**
     * 向玩家发送实体的发光颜色队伍关联数据包。
     */
    private static void sendGlowColorPacket(ServerPlayer viewer, Entity target, ChatFormatting color) {
        PlayerTeam team = GLOW_COLOR_TEAMS.get(color);
        if (team == null) return;
        // 将实体的 UUID 字符串加入虚拟队伍
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, target.getStringUUID(), ClientboundSetPlayerTeamPacket.Action.ADD));
    }

    /**
     * 向玩家发送移除实体发光颜色队伍关联的数据包（仅从指定颜色队伍移除）。
     */
    private static void removeGlowColorPacket(ServerPlayer viewer, Entity target, ChatFormatting color) {
        removeGlowColorPacket(viewer, target.getStringUUID(), color);
    }

    /**
     * 向玩家发送移除实体发光颜色队伍关联的数据包（使用已缓存的 UUID 字符串）。
     * 即使实体已不存在于服务端，只要有 UUID 字符串，就能正确清理客户端队伍列表。
     */
    private static void removeGlowColorPacket(ServerPlayer viewer, String entityUuidString, ChatFormatting color) {
        PlayerTeam team = GLOW_COLOR_TEAMS.get(color);
        if (team == null) return;
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, entityUuidString, ClientboundSetPlayerTeamPacket.Action.REMOVE));
    }

    /** 清除指定玩家的所有私有高亮（关闭功能或玩家登出时调用）。 */
    private static void clearPrivateHighlights(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            PRIVATE_HIGHLIGHT_COLORS.remove(player.getUUID());
            return;
        }

        Map<Integer, HighlightEntry> previous = PRIVATE_HIGHLIGHT_COLORS.remove(player.getUUID());
        if (previous == null || previous.isEmpty()) {
            return;
        }

        for (var entry : previous.entrySet()) {
            HighlightEntry hl = entry.getValue();
            // 始终发送队伍移除包，防止客户端内存泄漏（即使实体已不存在）
            removeGlowColorPacket(player, hl.entityUuidString(), hl.color());
            // 实体仍存活时才发送关闭发光包
            Entity maybeEntity = serverLevel.getEntity(entry.getKey());
            if (maybeEntity != null && maybeEntity.isAlive()) {
                sendPrivateGlowPacket(player, maybeEntity, false);
            }
        }
    }

    /** 从归属索引中移除已失效/被拾取的掉落物实体 ID。 */
    private static void untrackOwnedDropEntity(ItemEntity item) {
        if (!ModEntityData.has(item, ModAttachments.OWNER_UUID)) {
            return;
        }
        UUID owner = ModEntityData.get(item, ModAttachments.OWNER_UUID);
        Set<Integer> tracked = OWNED_DEATH_DROP_IDS.get(owner);
        if (tracked == null) {
            return;
        }
        tracked.remove(item.getId());
        ENTITY_DIMENSIONS.remove(item.getId());
        if (tracked.isEmpty()) {
            OWNED_DEATH_DROP_IDS.remove(owner);
            OWNER_SCOREBOARD_NAMES.remove(owner);
        }
    }

    /**
     * 清理指定玩家的已失效掉落物实体 ID（高亮功能关闭时的内存泄漏防护）。
     * <p>
     * 当 ITEM_GLOW_ENABLED 为 false 时，{@link #refreshPrivateHighlights} 不会被调用，
     * 因此无法依赖其扫描逻辑清除已消失实体的 ID。此方法填补这一清理路径的缺失，
     * 防止玩家多次死亡后 OWNED_DEATH_DROP_IDS 无限膨胀。
     */
    private static void cleanupStaleOwnedDropIds(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        UUID playerId = player.getUUID();
        Set<Integer> trackedEntityIds = OWNED_DEATH_DROP_IDS.get(playerId);
        if (trackedEntityIds == null || trackedEntityIds.isEmpty()) return;
        MinecraftServer server = player.getServer();

        List<Integer> stale = new ArrayList<>();
        for (Integer entityId : trackedEntityIds) {
            // 跨维度处理：在实体实际所在维度中查找，而非仅限玩家当前维度
            // 若 ENTITY_DIMENSIONS 中无记录（不应发生），视为在当前维度处理
            ResourceKey<Level> entityDimension = ENTITY_DIMENSIONS.get(entityId);
            ServerLevel entityLevel;
            if (entityDimension == null || entityDimension.equals(serverLevel.dimension())) {
                entityLevel = serverLevel;
            } else {
                entityLevel = server != null ? server.getLevel(entityDimension) : null;
                if (entityLevel == null) {
                    // 维度未加载，暂时跳过（不标记为失效）
                    continue;
                }
            }
            Entity maybeEntity = entityLevel.getEntity(entityId);
            if (maybeEntity == null) {
                // Null result could mean the entity is in an unloaded chunk, not necessarily gone.
                // Skip conservatively; real despawns are caught by onEntityLeaveLevel.
                continue;
            }
            if (!(maybeEntity instanceof ItemEntity item) || !item.isAlive()
                    || !ModEntityData.has(item, ModAttachments.OWNER_UUID)) {
                stale.add(entityId);
            }
        }
        if (!stale.isEmpty()) {
            trackedEntityIds.removeAll(stale);
            for (Integer staleId : stale) {
                ENTITY_DIMENSIONS.remove(staleId);
            }
            if (trackedEntityIds.isEmpty()) {
                OWNED_DEATH_DROP_IDS.remove(playerId);
                OWNER_SCOREBOARD_NAMES.remove(playerId);
            }
        }
    }

    /**
     * 向指定玩家发送实体发光状态的定向数据包。
     * 仅修改该玩家客户端的发光标志，不影响服务端实体状态。
     */
    private static void sendPrivateGlowPacket(ServerPlayer viewer, Entity target, boolean glow) {
        if (SHARED_FLAGS_ACCESSOR == null) {
            if (!SHARED_FLAGS_ACCESSOR_WARNED) {
                SHARED_FLAGS_ACCESSOR_WARNED = true;
                LOGGER.warn("Private glow packet skipped because shared flags accessor is unavailable");
            }
            return;
        }

        byte sharedFlags = target.getEntityData().get(SHARED_FLAGS_ACCESSOR);
        byte next = glow
                ? (byte) (sharedFlags | GLOWING_FLAG_MASK)
                : (byte) (sharedFlags & ~GLOWING_FLAG_MASK);

        var dataValue = new SynchedEntityData.DataValue<>(
                ENTITY_SHARED_FLAGS_DATA_ID,
                EntityDataSerializers.BYTE,
                next
        );

        viewer.connection.send(new ClientboundSetEntityDataPacket(target.getId(), List.of(dataValue)));
    }

    // ── 配置便捷读取 ────────────────────────────────────────────

    /**
     * 更新高频事件中使用的配置缓存。
     * 由 {@code ModConfigEvent.Loading} 和 {@code ModConfigEvent.Reloading} 触发调用，
     * 避免在 {@link #onServerTick} 等极高频事件中直接调用 {@code ConfigValue#get()}。
     * <p>
     * 也在此时修剪所有玩家的死亡快照队列，以便在管理员调低快照上限后立即释放内存，
     * 而不必等到每位玩家再次死亡才触发缩容逻辑。
     */
    public static void onConfigLoaded() {
        cachedVoidRecoveryEnabled = Config.COMMON.VOID_RECOVERY_ENABLED.get();
        cachedHazardRecoveryEnabled = Config.COMMON.HAZARD_RECOVERY_ENABLED.get();
        cachedVoidRecoveryMode = Config.COMMON.VOID_RECOVERY_MODE.get();
        trimAllSnapshotsToCurrentLimit();
    }

    /**
     * 将所有玩家的死亡快照队列修剪至当前配置的最大快照数。
     * <p>
     * 仅在队列大于上限时才执行 {@code removeLast}，因此对未超限玩家的开销为零。
     */
    private static void trimAllSnapshotsToCurrentLimit() {
        if (DEATH_DROP_SNAPSHOTS.isEmpty()) return;
        int maxSnapshots = Math.max(1, Config.COMMON.DEATH_DROP_SNAPSHOT_MAX_PER_PLAYER.get());
        for (Deque<DeathDropSnapshot> deque : DEATH_DROP_SNAPSHOTS.values()) {
            while (deque.size() > maxSnapshots) {
                deque.removeLast();
            }
        }
    }

    private static int getPrivateHighlightIntervalTicks() {
        return Math.max(1, Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_INTERVAL_TICKS.get());
    }

    private static double getPrivateHighlightRadius() {
        return Math.max(8.0, Config.COMMON.PRIVATE_HIGHLIGHT_SCAN_RADIUS.get());
    }

    private static int getPrivateHighlightMaxScannedEntities() {
        return Math.max(16, Config.COMMON.PRIVATE_HIGHLIGHT_MAX_SCANNED_ENTITIES.get());
    }

    private static boolean isVoidRecoveryDebugEnabled() {
        return voidRecoveryDebug;
    }

    /** 设置虚空恢复调试开关（供命令调用）。 */
    public static void setVoidRecoveryDebug(boolean enabled) {
        voidRecoveryDebug = enabled;
    }

    /** 获取虚空恢复调试开关当前值。 */
    public static boolean getVoidRecoveryDebug() {
        return voidRecoveryDebug;
    }

    // ── 调试状态查询（供 ConfigCommands 调用） ─────────────────────

    /** 反射访问器是否可用。 */
    public static boolean isSharedFlagsAccessorReady() {
        return SHARED_FLAGS_ACCESSOR != null;
    }

    public static int getPrivateHighlightTrackedPlayerCount() {
        return PRIVATE_HIGHLIGHT_COLORS.size();
    }

    public static int getSavedItemsPlayerCount() {
        return SAVED_ITEMS.size();
    }

    public static int getDeathDropSnapshotPlayerCount() {
        return DEATH_DROP_SNAPSHOTS.size();
    }

    public static int getInventorySnapshotPlayerCount() {
        return INVENTORY_SNAPSHOTS.size();
    }

    public static int getPendingDeathPositionPlayerCount() {
        return PENDING_DEATH_POS.size();
    }

    // ── 物品槽位操作 ──────────────────────────────────────────────

    /**
     * 尝试将物品插入指定槽位，支持同类堆叠。
     *
     * @return 未能插入的剩余物品（空则表示全部插入）
     */
    private static ItemStack insertIntoSlot(Inventory inventory, int slot, ItemStack stackToInsert) {
        if (stackToInsert.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack current = inventory.getItem(slot);
        if (current.isEmpty()) {
            inventory.setItem(slot, stackToInsert);
            return ItemStack.EMPTY;
        }

        if (!ItemStack.isSameItemSameComponents(current, stackToInsert) || !current.isStackable()) {
            return stackToInsert;
        }

        int maxStackSize = Math.min(
            Math.min(current.getMaxStackSize(), stackToInsert.getMaxStackSize()),
            inventory.getMaxStackSize()
        );
        int room = maxStackSize - current.getCount();
        if (room <= 0) {
            return stackToInsert;
        }

        int move = Math.min(room, stackToInsert.getCount());
        current.grow(move);
        stackToInsert.shrink(move);
        inventory.setItem(slot, current);

        return stackToInsert;
    }
}