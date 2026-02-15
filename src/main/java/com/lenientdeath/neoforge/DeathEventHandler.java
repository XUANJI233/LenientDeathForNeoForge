package com.lenientdeath.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.common.util.TriState;

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

@SuppressWarnings({"unused", "null"})
@EventBusSubscriber(modid = LenientDeathNeoForge.MODID)
public class DeathEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/DeathEventHandler");
    private record SavedItem(ItemStack stack, int originalSlot) {}
    private record RecoveryTarget(BlockPos pos, String source) {}

    private static final int SAFE_POS_UPDATE_TICKS = 10;
    private static final int SAFE_POS_HISTORY_LIMIT = 12;
    private static final int ENTITY_SHARED_FLAGS_DATA_ID = 0;
    private static final byte GLOWING_FLAG_MASK = 0x40;

    private static final EntityDataAccessor<Byte> SHARED_FLAGS_ACCESSOR = resolveSharedFlagsAccessor();
    private static volatile boolean SHARED_FLAGS_ACCESSOR_WARNED = false;

    private static final Map<UUID, List<SavedItem>> SAVED_ITEMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Integer, ItemStack>> INVENTORY_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<GlobalPos>> SAFE_POS_HISTORY = new ConcurrentHashMap<>();
    private static final Map<UUID, GlobalPos> PENDING_DEATH_POS = new ConcurrentHashMap<>();
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
     * 1. 实时记录玩家的安全位置 (用于防虚空)
     * 性能优化：每 10 tick (0.5秒) 检查一次，只在地面上时更新
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
            BlockPos safePos = resolveStandingSafePos(serverLevel, currentBlockPos);
            GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), safePos.immutable());
            pushSafePosHistory(player.getUUID(), currentPos);
            ModEntityData.put(player, ModAttachments.SAFE_RECOVERY_POS, currentPos);
        }

        // No periodic scanning here — pickup is handled via ItemEntityPickupEvent for performance.
    }

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
                // Mutate the live stack reference instead of calling setItem in Pre (undefined behavior).
                entityStack.shrink(moved);
                player.take(entity, moved);

                if (entityStack.isEmpty()) {
                    entity.discard();
                }

                // We already transferred part/all items manually, deny default pickup this tick.
                event.setCanPickup(TriState.FALSE);
            }
        }
    }

    /**
     * 2. 玩家死亡瞬间：记录坐标 & 拍摄背包快照
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
     * 3. 掉落物生成：核心处理
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

            // ORIGINAL_SLOT 已在进入循环时统一匹配并写入
        }

        // 保存保留的物品
        if (!keptItems.isEmpty()) {
            SAVED_ITEMS.put(player.getUUID(), keptItems);
        }
    }

    /**
     * 4. 虚空救援 (高性能版)
     */
    @SubscribeEvent
    @SuppressWarnings("ConstantConditions")
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!Config.COMMON.VOID_RECOVERY_ENABLED.get()) return;
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (item.level().isClientSide) return;

        Config.VoidRecoveryMode recoveryMode = Config.COMMON.VOID_RECOVERY_MODE.get();
        if (recoveryMode == Config.VoidRecoveryMode.DEATH_DROPS_ONLY
                && (!ModEntityData.has(item, ModAttachments.IS_DEATH_DROP)
                || !ModEntityData.get(item, ModAttachments.IS_DEATH_DROP))) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.debug("[VoidRecovery] Skip item {} mode={} reason=not_death_drop at ({}, {}, {})",
                        item.getId(), recoveryMode, item.getX(), item.getY(), item.getZ());
            }
            return;
        }

        var lvl = item.level();
        double triggerY = lvl.getMinBuildHeight() - 16.0;
        double currentY = item.getY();
        double predictedNextY = currentY + item.getDeltaMovement().y;
        if (currentY > triggerY && predictedNextY > triggerY) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.debug("[VoidRecovery] Skip item {} reason=above_trigger triggerY={} currentY={} nextY={}",
                        item.getId(), triggerY, currentY, predictedNextY);
            }
            return;
        }

        // 当前帧或下一帧将越过阈值时就触发，避免高速下坠跨帧漏判
        if (!canRecoverFromVoidNow(item)) {
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.debug("[VoidRecovery] Skip item {} reason=limiter_blocked at ({}, {}, {})",
                        item.getId(), item.getX(), item.getY(), item.getZ());
            }
            return;
        }

        if (lvl instanceof ServerLevel serverLevel) {
            RecoveryTarget recoveryTarget = resolveRecoveryTarget(serverLevel, item);
            teleportItemToSafety(item, recoveryTarget.pos());
            if (isVoidRecoveryDebugEnabled()) {
                LOGGER.debug("[VoidRecovery] Recover item {} mode={} source={} from ({}, {}, {}) -> ({}, {}, {})",
                        item.getId(), recoveryMode, recoveryTarget.source(),
                        item.getX(), item.getY(), item.getZ(),
                        recoveryTarget.pos().getX(), recoveryTarget.pos().getY(), recoveryTarget.pos().getZ());
            }
        }
    }

    private static RecoveryTarget resolveRecoveryTarget(ServerLevel level, ItemEntity item) {
        BlockPos fromCurrentColumn = findSurfaceTarget(level, item.blockPosition());
        if (fromCurrentColumn != null) {
            return new RecoveryTarget(fromCurrentColumn, "nearby_surface");
        }

        GlobalPos safePos = ModEntityData.has(item, ModAttachments.SAFE_RECOVERY_POS)
                ? ModEntityData.get(item, ModAttachments.SAFE_RECOVERY_POS)
                : null;

        if (safePos != null && safePos.dimension() == level.dimension()) {
            BlockPos fromSafe = findSurfaceTarget(level, safePos.pos());
            if (fromSafe != null) {
                return new RecoveryTarget(fromSafe, "item_safe_pos");
            }
        }

        if (ModEntityData.has(item, ModAttachments.OWNER_UUID)) {
            UUID ownerId = ModEntityData.get(item, ModAttachments.OWNER_UUID);
            GlobalPos historical = getBestHistoricalSafePos(ownerId, level.dimension(), item.blockPosition());
            if (historical != null) {
                BlockPos fromHistory = findSurfaceTarget(level, historical.pos());
                if (fromHistory != null) {
                    return new RecoveryTarget(fromHistory, "owner_history");
                }
            }
        }

        BlockPos spawnPos = level.getSharedSpawnPos();
        BlockPos fromSpawn = findSurfaceTarget(level, spawnPos);
        if (fromSpawn != null) {
            return new RecoveryTarget(fromSpawn, "spawn_surface");
        }

        int fallbackY = Math.max(level.getMinBuildHeight() + 1, level.getSeaLevel());
        return new RecoveryTarget(new BlockPos(spawnPos.getX(), fallbackY, spawnPos.getZ()), "spawn_fallback");
    }

    private static BlockPos findSurfaceTarget(ServerLevel level, BlockPos center) {
        for (int radius = 0; radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (topY <= level.getMinBuildHeight()) {
                        continue;
                    }

                    BlockPos above = new BlockPos(x, topY, z);
                    if (!level.getBlockState(above.below()).isAir()) {
                        return above;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos resolveStandingSafePos(ServerLevel level, BlockPos playerPos) {
        BlockPos below = playerPos.below();
        if (!level.getBlockState(below).isAir()) {
            return playerPos;
        }

        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, playerPos.getX(), playerPos.getZ());
        if (topY > level.getMinBuildHeight()) {
            return new BlockPos(playerPos.getX(), topY, playerPos.getZ());
        }

        return playerPos;
    }

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

    private static GlobalPos getBestHistoricalSafePos(UUID playerId, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos nearPos) {
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

    // 辅助方法：安全的传送物品
    private static void teleportItemToSafety(ItemEntity item, BlockPos pos) {
        // 传送到方块中心上方
        item.teleportTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        // 关键：重置速度，否则它会带着巨大的下坠速度继续穿过方块掉下去
        item.setDeltaMovement(Vec3.ZERO);
        item.setNoGravity(false);
        // 给予极短拾取冷却，避免传送帧边缘被重复处理
        item.setPickUpDelay(10);
        // 发光由私有高亮系统处理，不在实体上设置全局 glowing。
    }

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
        return Config.COMMON.VOID_RECOVERY_DEBUG_ENABLED.get();
    }

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