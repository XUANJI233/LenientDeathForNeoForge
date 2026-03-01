package com.lenientdeath.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    // ── 常量 ──────────────────────────────────────────────────────

    /** 安全位置更新间隔（tick），每 0.5 秒记录一次。 */
    private static final int SAFE_POS_UPDATE_TICKS = 10;
    /** 安全位置历史记录上限。 */
    private static final int SAFE_POS_HISTORY_LIMIT = 12;
    /** 虚空恢复触发偏移量：物品 Y 低于 (minBuildHeight - offset) 时触发。 */
    private static final double VOID_RECOVERY_TRIGGER_OFFSET = 8.0;
    /** 即时虚空恢复判定余量：玩家死亡 Y 低于 (minBuildHeight + margin) 时，在掉落物生成时立即恢复。 */
    private static final double IMMEDIATE_VOID_RECOVERY_Y_MARGIN = 8.0;
    /** Entity shared flags 同步数据的 slot ID（固定为 0）。 */
    private static final int ENTITY_SHARED_FLAGS_DATA_ID = 0;
    /** Entity shared flags 中发光位的掩码。 */
    private static final byte GLOWING_FLAG_MASK = 0x40;

    // ── 反射获取的访问器 ──────────────────────────────────────────

    /** 通过反射获取的 Entity.DATA_SHARED_FLAGS_ID，用于发送私有发光数据包。 */
    private static final EntityDataAccessor<Byte> SHARED_FLAGS_ACCESSOR = resolveSharedFlagsAccessor();
    /** 反射失败时只警告一次的标志位（volatile 保证多线程可见性）。 */
    private static volatile boolean SHARED_FLAGS_ACCESSOR_WARNED = false;

    /**
     * 虚空恢复调试开关（仅运行时，不持久化到配置文件）。
     * <p>
     * 每次服务器/世界加载后自动重置为 {@code false}，需手动通过命令开启。
     */
    private static volatile boolean voidRecoveryDebug = false;

    // ── 运行时状态（按玩家 UUID 索引） ────────────────────────────

    /** 死亡时保留的物品，在重生（Clone 事件）时还原。 */
    private static final Map<UUID, List<SavedItem>> SAVED_ITEMS = new ConcurrentHashMap<>();
    /** 死亡时的背包快照，用于匹配掉落物的原始槽位。 */
    private static final Map<UUID, Map<Integer, ItemStack>> INVENTORY_SNAPSHOTS = new ConcurrentHashMap<>();
    /** 玩家安全位置的历史队列，用于恢复时查找最近的安全点。 */
    private static final Map<UUID, Deque<GlobalPos>> SAFE_POS_HISTORY = new ConcurrentHashMap<>();
    /** 待发送的死亡坐标消息（等到重生后发送更稳定）。 */
    private static final Map<UUID, GlobalPos> PENDING_DEATH_POS = new ConcurrentHashMap<>();
    /** 每个玩家当前可见的私有高亮实体 ID 集合。 */
    private static final Map<UUID, Set<Integer>> PRIVATE_HIGHLIGHTED_ENTITY_IDS = new ConcurrentHashMap<>();

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
        if (!Config.COMMON.RESTORE_SLOTS_ENABLED.get()) return;

        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        ItemEntity entity = event.getItemEntity();

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
            PENDING_DEATH_POS.put(player.getUUID(), GlobalPos.of(lvl.dimension(), player.blockPosition()));
        }

        // 背包快照 (用于恢复槽位)
        if (Config.COMMON.RESTORE_SLOTS_ENABLED.get()) {
            Map<Integer, ItemStack> snapshot = new HashMap<>();
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    snapshot.put(i, stack.copy());
                }
            }
            INVENTORY_SNAPSHOTS.put(player.getUUID(), snapshot);
        }
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
        // 获取玩家历史安全点中的最佳候选（优先同维度且接近死亡点）
        GlobalPos lastSafePos = getBestHistoricalSafePos(player.getUUID(), player.level().dimension(), player.blockPosition());
        if (lastSafePos == null && ModEntityData.has(player, ModAttachments.SAFE_RECOVERY_POS)) {
            lastSafePos = ModEntityData.get(player, ModAttachments.SAFE_RECOVERY_POS);
        }

        ServerLevel serverLevel = player.level() instanceof ServerLevel level ? level : null;
        boolean immediateVoidRecovery = serverLevel != null
            && Config.COMMON.VOID_RECOVERY_ENABLED.get()
            && shouldImmediateVoidRecover(serverLevel, player.getY());

        while (iterator.hasNext()) {
            ItemEntity entity = iterator.next();
            ItemStack stack = entity.getItem();
            int matchedSlot = -1;

            if (Config.COMMON.RESTORE_SLOTS_ENABLED.get() && snapshot != null) {
                var match = snapshot.entrySet().stream()
                        .filter(e -> ItemStack.isSameItemSameComponents(e.getValue(), stack))
                        .findFirst();

                if (match.isPresent()) {
                    matchedSlot = match.get().getKey();
                    snapshot.remove(matchedSlot);
                    ModEntityData.put(entity, ModAttachments.ORIGINAL_SLOT, matchedSlot);
                }
            }

            // 标记掉落物归属，供私有高亮使用
            ModEntityData.put(entity, ModAttachments.OWNER_UUID, player.getUUID());
            ModEntityData.put(entity, ModAttachments.IS_DEATH_DROP, true);

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

            // 2. 防爆/防火
            if (Config.COMMON.ITEM_RESILIENCE_ENABLED.get()) {
                entity.setInvulnerable(true);
                entity.setUnlimitedLifetime();
            }

            // 3. 写入安全位置数据 (用于防虚空)
            // 即使现在没掉进虚空，也要把这个“回家坐标”写在物品身上，万一它以后掉下去了呢
            if (lastSafePos != null) {
                ModEntityData.put(entity, ModAttachments.SAFE_RECOVERY_POS, lastSafePos);
            }

            if (immediateVoidRecovery && serverLevel != null) {
                attemptImmediateRecovery(serverLevel, entity, "death_drop_immediate_void");
            }

            // ORIGINAL_SLOT 已在进入循环时统一匹配并写入
        }

        // 保存保留的物品
        if (!keptItems.isEmpty()) {
            SAVED_ITEMS.put(player.getUUID(), keptItems);
        }
    }

    /**
     * 实体 tick 前处理：对 ItemEntity 执行虚空/危险恢复。
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (item.level().isClientSide) return;

        boolean voidRecoveryEnabled = Config.COMMON.VOID_RECOVERY_ENABLED.get();
        boolean hazardRecoveryEnabled = Config.COMMON.HAZARD_RECOVERY_ENABLED.get();
        
        if (!voidRecoveryEnabled && !hazardRecoveryEnabled) return;

        Config.VoidRecoveryMode recoveryMode = Config.COMMON.VOID_RECOVERY_MODE.get();
        boolean isDeathDrop = ModEntityData.has(item, ModAttachments.IS_DEATH_DROP) 
                && ModEntityData.get(item, ModAttachments.IS_DEATH_DROP);
        
        // 根据模式过滤非死亡掉落物
        if (recoveryMode == Config.VoidRecoveryMode.DEATH_DROPS_ONLY && !isDeathDrop) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.info("[LenientDeath][Recovery] Skip item {} mode={} reason=not_death_drop at ({}, {}, {})",
                        item.getId(), recoveryMode, item.getX(), item.getY(), item.getZ());
            }
            return;
        }

        // 检查是否刚刚恢复过（避免同一tick重复处理）
        // 使用恢复时的tick记录，仅跳过同一tick内的重复触发
        if (ModEntityData.has(item, ModAttachments.VOID_RECOVERED)) {
            int recoveredAtTick = ModEntityData.get(item, ModAttachments.VOID_RECOVERED);
            if (recoveredAtTick >= 0 && item.tickCount - recoveredAtTick < 2) {
                return;
            }
        }

        var lvl = item.level();
        String recoveryReason = null;
        
        // 检查虚空
        if (voidRecoveryEnabled) {
            double triggerY = getVoidTriggerY(lvl.getMinBuildHeight());
            double currentY = item.getY();
            double predictedNextY = currentY + item.getDeltaMovement().y;
            if (currentY <= triggerY || predictedNextY <= triggerY) {
                recoveryReason = "void";
            }
        }
        
        // 检查火焰/岩浆（仅当未触发虚空恢复时）
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

        // 限流检查
        if (!canRecoverFromVoidNow(item)) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.info("[LenientDeath][Recovery] Skip item {} reason=limiter_blocked at ({}, {}, {})",
                        item.getId(), item.getX(), item.getY(), item.getZ());
            }
            return;
        }

        if (lvl instanceof ServerLevel serverLevel) {
            // 在传送前记录原始位置用于日志
            double fromX = item.getX();
            double fromY = item.getY();
            double fromZ = item.getZ();

            RecoveryTarget recoveryTarget = resolveRecoveryTarget(serverLevel, item);
            teleportItemToSafety(item, recoveryTarget.pos());
            
            // 火焰/岩浆恢复后灭火
            if ("lava".equals(recoveryReason) || "fire".equals(recoveryReason)) {
                item.clearFire();
            }

            // 标记恢复tick，避免同tick重复处理（但允许之后再次被拯救）
            ModEntityData.put(item, ModAttachments.VOID_RECOVERED, item.tickCount);

            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.info("[LenientDeath][Recovery] Recover item {} mode={} trigger={} source={} from ({}, {}, {}) -> ({}, {}, {})",
                        item.getId(), recoveryMode, recoveryReason, recoveryTarget.source(),
                        fromX, fromY, fromZ,
                        recoveryTarget.pos().getX() + 0.5, recoveryTarget.pos().getY(), recoveryTarget.pos().getZ() + 0.5);
            }
        }
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
    private static RecoveryTarget resolveRecoveryTarget(ServerLevel level, ItemEntity item) {
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

        int fallbackY = Math.max(level.getMinBuildHeight() + 1, level.getSeaLevel());
        return new RecoveryTarget(new BlockPos(spawnPos.getX(), fallbackY, spawnPos.getZ()), "spawn_fallback");
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

        // 安全点附近微调，避免目标点刚好被临时方块占用
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = preferredPos.offset(dx, 0, dz);
                    if (isValidRecoverySpot(level, item, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在以 center 为中心的 3D 范围内搜索距离最近的安全位置。
     *
     * @param horizontalRadius 水平搜索半径
     * @param verticalRange    垂直搜索范围（上下各此值）
     * @return 最近安全位置，找不到则返回 null
     */
    private static BlockPos findNearestSafeSpot(ServerLevel level, ItemEntity item, BlockPos center, int horizontalRadius, int verticalRange) {
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;

        int minY = Math.max(level.getMinBuildHeight() + 1, center.getY() - verticalRange);
        int maxY = Math.min(level.getMaxBuildHeight() - 2, center.getY() + verticalRange);

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos candidate = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (!isValidRecoverySpot(level, item, candidate)) {
                        continue;
                    }

                    double distanceSq = candidate.distSqr(center);
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = candidate;
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
        BlockPos floorPos = feetPos.below();
        BlockPos headPos = feetPos.above();

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

        double targetX = feetPos.getX() + 0.5;
        double targetY = feetPos.getY();
        double targetZ = feetPos.getZ() + 0.5;
        var movedBox = item.getBoundingBox().move(targetX - item.getX(), targetY - item.getY(), targetZ - item.getZ());
        return level.noCollision(item, movedBox);
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
     */
    private static void teleportItemToSafety(ItemEntity item, BlockPos pos) {
        double targetX = pos.getX() + 0.5;
        double targetY = pos.getY();
        double targetZ = pos.getZ() + 0.5;
        
        // 重置速度，避免传送后继续下坠
        item.setDeltaMovement(Vec3.ZERO);
        
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
     * 即时恢复：在掉落物生成时立即传送到安全位置（用于边缘场景如近虚空死亡）。
     */
    private static void attemptImmediateRecovery(ServerLevel level, ItemEntity item, String reason) {
        if (ModEntityData.has(item, ModAttachments.VOID_RECOVERED)) {
            int recoveredAtTick = ModEntityData.get(item, ModAttachments.VOID_RECOVERED);
            if (recoveredAtTick >= 0 && item.tickCount - recoveredAtTick < 2) {
                return;
            }
        }

        double fromX = item.getX();
        double fromY = item.getY();
        double fromZ = item.getZ();

        RecoveryTarget recoveryTarget = resolveRecoveryTarget(level, item);
        teleportItemToSafety(item, recoveryTarget.pos());
        ModEntityData.put(item, ModAttachments.VOID_RECOVERED, item.tickCount);

        if (isVoidRecoveryDebugEnabled()) {
            LOGGER.info("[LenientDeath][Recovery] Recover item {} mode={} trigger={} source={} from ({}, {}, {}) -> ({}, {}, {})",
                    item.getId(), Config.COMMON.VOID_RECOVERY_MODE.get(), reason, recoveryTarget.source(),
                    fromX, fromY, fromZ,
                    recoveryTarget.pos().getX() + 0.5, recoveryTarget.pos().getY(), recoveryTarget.pos().getZ() + 0.5);
        }
    }

    /**
     * 玩家重生时：发送死亡坐标、还原保留物品、继承安全位置。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        ServerPlayer newPlayer = (ServerPlayer) event.getEntity();
        UUID uuid = event.getOriginal().getUUID();

        // 仅给死亡玩家自己发送死亡坐标消息（重生后发送更稳定）
        GlobalPos deathPos = PENDING_DEATH_POS.remove(uuid);
        if (deathPos != null && Config.COMMON.DEATH_COORDS_ENABLED.get()) {
            newPlayer.sendSystemMessage(Component.translatable(
                "lenientdeath.death_message",
                deathPos.pos().getX(),
                deathPos.pos().getY(),
                deathPos.pos().getZ(),
                deathPos.dimension().location().toString()
            ).withStyle(ChatFormatting.YELLOW));
        }

        // 恢复保留物品
        if (SAVED_ITEMS.containsKey(uuid)) {
            List<SavedItem> items = SAVED_ITEMS.get(uuid);
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
            SAVED_ITEMS.remove(uuid);
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
        PRIVATE_HIGHLIGHTED_ENTITY_IDS.remove(uuid);
    }

    /**
     * 刷新指定玩家的私有高亮：扫描附近归属该玩家的 ItemEntity，
     * 发送发光数据包使仅该玩家可见。
     */
    private static void refreshPrivateHighlights(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        double scanRadius = getPrivateHighlightRadius();
        int maxScannedEntities = getPrivateHighlightMaxScannedEntities();

        UUID playerId = player.getUUID();
        Set<Integer> previous = PRIVATE_HIGHLIGHTED_ENTITY_IDS.computeIfAbsent(playerId, ignored -> new HashSet<>());
        Set<Integer> current = new HashSet<>();

        List<ItemEntity> nearbyItems = serverLevel.getEntitiesOfClass(
                ItemEntity.class,
            player.getBoundingBox().inflate(scanRadius),
                item -> item.isAlive() && ModEntityData.has(item, ModAttachments.OWNER_UUID)
        );

        int processed = 0;
        for (ItemEntity item : nearbyItems) {
            if (processed >= maxScannedEntities) {
                break;
            }
            processed++;

            UUID owner = ModEntityData.get(item, ModAttachments.OWNER_UUID);
            if (Objects.equals(owner, playerId)) {
                int entityId = item.getId();
                current.add(entityId);
                if (!previous.contains(entityId)) {
                    sendPrivateGlowPacket(player, item, true);
                }
            }
        }

        for (Integer entityId : previous) {
            if (!current.contains(entityId)) {
                Entity maybeEntity = serverLevel.getEntity(entityId);
                if (maybeEntity != null && maybeEntity.isAlive()) {
                    sendPrivateGlowPacket(player, maybeEntity, false);
                }
            }
        }

        PRIVATE_HIGHLIGHTED_ENTITY_IDS.put(playerId, current);
    }

    /** 清除指定玩家的所有私有高亮（关闭功能或玩家登出时调用）。 */
    private static void clearPrivateHighlights(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            PRIVATE_HIGHLIGHTED_ENTITY_IDS.remove(player.getUUID());
            return;
        }

        Set<Integer> previous = PRIVATE_HIGHLIGHTED_ENTITY_IDS.remove(player.getUUID());
        if (previous == null || previous.isEmpty()) {
            return;
        }

        for (Integer entityId : previous) {
            Entity maybeEntity = serverLevel.getEntity(entityId);
            if (maybeEntity != null && maybeEntity.isAlive()) {
                sendPrivateGlowPacket(player, maybeEntity, false);
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
        return PRIVATE_HIGHLIGHTED_ENTITY_IDS.size();
    }

    public static int getSavedItemsPlayerCount() {
        return SAVED_ITEMS.size();
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