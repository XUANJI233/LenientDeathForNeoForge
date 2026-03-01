package com.lenientdeath.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 注册所有 NeoForge 附件类型（Attachment），用于在实体上存储额外数据。
 * <p>
 * 附件通过 {@link ModEntityData} 的 put/get/has 方法进行读写。
 */
@SuppressWarnings("null")
public final class ModAttachments {
    private ModAttachments() {
        // 工具类，禁止实例化
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LenientDeathNeoForge.MODID);

    /** 记录物品原始槽位索引，用于死亡掉落物拾取时还原到原始背包位置。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Integer>> ORIGINAL_SLOT = ATTACHMENT_TYPES.register(
            "original_slot",
            () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
    );

    /** 记录安全落地坐标（维度 + 坐标），用于虚空/危险恢复时传送物品。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<GlobalPos>> SAFE_RECOVERY_POS = ATTACHMENT_TYPES.register(
            "safe_recovery_pos",
            () -> AttachmentType.builder(() -> GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO))
                    .serialize(GlobalPos.CODEC).build()
    );

    /** 记录掉落物归属玩家 UUID，用于私有高亮（仅归属玩家可见发光效果）。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<UUID>> OWNER_UUID = ATTACHMENT_TYPES.register(
            "owner_uuid",
            () -> AttachmentType.builder(() -> new UUID(0L, 0L)).serialize(UUIDUtil.CODEC).build()
    );

    /** 虚空恢复限流：当前窗口起始 tick。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_WINDOW_START_TICK = ATTACHMENT_TYPES.register(
            "void_recovery_window_start_tick",
            () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
    );

    /** 虚空恢复限流：当前窗口内已恢复次数。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_COUNT_IN_WINDOW = ATTACHMENT_TYPES.register(
            "void_recovery_count_in_window",
            () -> AttachmentType.builder(() -> 0).serialize(com.mojang.serialization.Codec.INT).build()
    );

    /** 虚空恢复限流：冷却截止 tick，在此之前不再触发恢复。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_COOLDOWN_UNTIL_TICK = ATTACHMENT_TYPES.register(
            "void_recovery_cooldown_until_tick",
            () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
    );

    /** 标记是否为玩家死亡产生的掉落物，用于按恢复模式过滤非死亡掉落物。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Boolean>> IS_DEATH_DROP = ATTACHMENT_TYPES.register(
            "is_death_drop",
            () -> AttachmentType.builder(() -> false).serialize(com.mojang.serialization.Codec.BOOL).build()
    );

    /** 记录上次恢复时的 tick，防止同 tick 重复处理，但允许之后再次被拯救。 */
    @SuppressWarnings("unused") // 由 DeathEventHandler 通过 ModEntityData 引用
    public static final Supplier<AttachmentType<Integer>> VOID_RECOVERED = ATTACHMENT_TYPES.register(
            "void_recovered",
            () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
    );

    /**
     * 将附件类型注册到模组事件总线。
     *
     * @param bus 模组事件总线
     */
    public static void register(IEventBus bus) {
        Objects.requireNonNull(bus, "bus");
        ATTACHMENT_TYPES.register(bus);
    }
}