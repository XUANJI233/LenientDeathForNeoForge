package com.lenientdeath.neoforge;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public final class ModAttachments {
    private ModAttachments() {
        // utility class
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LenientDeathNeoForge.MODID);

    // 附件1：记录物品原始槽位 (Integer)
    @SuppressWarnings("unused")
    public static final Supplier<AttachmentType<Integer>> ORIGINAL_SLOT = ATTACHMENT_TYPES.register(
            "original_slot",
            () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
    );

    // 附件2：记录安全落地坐标 (GlobalPos = 维度 + 坐标)
    // 用于玩家(记录) 和 掉落物(读取)
    @SuppressWarnings("unused")
    public static final Supplier<AttachmentType<GlobalPos>> SAFE_RECOVERY_POS = ATTACHMENT_TYPES.register(
            "safe_recovery_pos",
            () -> AttachmentType.builder(() -> GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)).serialize(GlobalPos.CODEC).build()
    );

        // 附件3：记录掉落物归属玩家 UUID（用于仅对归属玩家显示高亮）
        @SuppressWarnings("unused")
        public static final Supplier<AttachmentType<UUID>> OWNER_UUID = ATTACHMENT_TYPES.register(
            "owner_uuid",
            () -> AttachmentType.builder(() -> new UUID(0L, 0L)).serialize(UUIDUtil.CODEC).build()
        );

            // 附件4：虚空恢复窗口起始tick
            @SuppressWarnings("unused")
            public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_WINDOW_START_TICK = ATTACHMENT_TYPES.register(
                "void_recovery_window_start_tick",
                () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
            );

            // 附件5：虚空恢复窗口内已恢复次数
            @SuppressWarnings("unused")
            public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_COUNT_IN_WINDOW = ATTACHMENT_TYPES.register(
                "void_recovery_count_in_window",
                () -> AttachmentType.builder(() -> 0).serialize(com.mojang.serialization.Codec.INT).build()
            );

            // 附件6：虚空恢复冷却截止tick
            @SuppressWarnings("unused")
            public static final Supplier<AttachmentType<Integer>> VOID_RECOVERY_COOLDOWN_UNTIL_TICK = ATTACHMENT_TYPES.register(
                "void_recovery_cooldown_until_tick",
                () -> AttachmentType.builder(() -> -1).serialize(com.mojang.serialization.Codec.INT).build()
            );

    public static void register(IEventBus bus) {
        Objects.requireNonNull(bus, "bus");
        ATTACHMENT_TYPES.register(bus);
    }
}