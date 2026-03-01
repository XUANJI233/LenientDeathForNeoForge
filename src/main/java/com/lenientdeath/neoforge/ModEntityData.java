package com.lenientdeath.neoforge;

import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 实体附件数据的便捷操作封装，统一 {@code setData}/{@code getData}/{@code hasData} 调用。
 */
public final class ModEntityData {
    private ModEntityData() {}

    /** 将数据写入实体附件。 */
    public static <T> void put(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment, T value) {
        entity.setData(Objects.requireNonNull(attachment.get(), "attachment"), Objects.requireNonNull(value, "value"));
    }

    /** 读取实体附件数据。 */
    public static <T> T get(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment) {
        return entity.getData(Objects.requireNonNull(attachment.get(), "attachment"));
    }

    /** 检查实体是否拥有指定附件。 */
    public static <T> boolean has(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment) {
        return entity.hasData(Objects.requireNonNull(attachment.get(), "attachment"));
    }
}

