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
import java.util.Collection;
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

    private static final int SAFE_POS_UPDATE_TICKS = 10;
    private static final int ENTITY_SHARED_FLAGS_DATA_ID = 0;
    private static final byte GLOWING_FLAG_MASK = 0x40;

    private static final EntityDataAccessor<Byte> SHARED_FLAGS_ACCESSOR = resolveSharedFlagsAccessor();
    private static volatile boolean SHARED_FLAGS_ACCESSOR_WARNED = false;

    private static final Map<UUID, List<SavedItem>> SAVED_ITEMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Integer, ItemStack>> INVENTORY_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> LAST_SAFE_BLOCK_POS = new ConcurrentHashMap<>();
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

        // 只有玩家站在地面上，且不是观察者模式时，才记录位置
        if (player.tickCount % SAFE_POS_UPDATE_TICKS == 0 && player.onGround() && !player.isSpectator()) {
            BlockPos currentBlockPos = player.blockPosition();
            BlockPos previous = LAST_SAFE_BLOCK_POS.get(player.getUUID());
            if (currentBlockPos.equals(previous)) return;

            var lvl = player.level();
            GlobalPos currentPos = GlobalPos.of(lvl.dimension(), currentBlockPos);
            // 存入玩家的数据附件中
            ModEntityData.put(player, ModAttachments.SAFE_RECOVERY_POS, currentPos);
            LAST_SAFE_BLOCK_POS.put(player.getUUID(), currentBlockPos.immutable());
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
        // 获取玩家重生前最后记录的安全位置
        GlobalPos lastSafePos = ModEntityData.has(player, ModAttachments.SAFE_RECOVERY_POS)
            ? ModEntityData.get(player, ModAttachments.SAFE_RECOVERY_POS)
            : null;

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

        var lvl = item.level();
        double triggerY = lvl.getMinBuildHeight() - 16.0;
        if (item.getY() >= triggerY) return;

        // 只有当掉落到世界最低高度以下 16 格时才触发
        if (!canRecoverFromVoidNow(item)) {
            return;
        }

        // 读取物品身上记录的“安全位置”
            GlobalPos safePos = ModEntityData.has(item, ModAttachments.SAFE_RECOVERY_POS)
                ? ModEntityData.get(item, ModAttachments.SAFE_RECOVERY_POS)
                : null;

        if (safePos != null && safePos.dimension() == lvl.dimension()) {
            // 维度相同：直接传送
            teleportItemToSafety(item, safePos.pos());
        } else {
            // 其他情况：使用兜底策略
            BlockPos fallback = new BlockPos(item.getBlockX(), lvl.getMinBuildHeight() + 20, item.getBlockZ());
            teleportItemToSafety(item, fallback);
        }
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
            LAST_SAFE_BLOCK_POS.put(newPlayer.getUUID(), oldSafePos.pos().immutable());
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
        LAST_SAFE_BLOCK_POS.remove(uuid);
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