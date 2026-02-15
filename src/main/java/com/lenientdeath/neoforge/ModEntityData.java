package com.lenientdeath.neoforge;

import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.Objects;
import java.util.function.Supplier;

public final class ModEntityData {
    private ModEntityData() {}

    public static <T> void put(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment, T value) {
        // entity.setData exists on runtime mappings used by NeoForge; wrap it here for future-proofing
        entity.setData(Objects.requireNonNull(attachment.get(), "attachment"), Objects.requireNonNull(value, "value"));
    }

    public static <T> T get(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment) {
        return entity.getData(Objects.requireNonNull(attachment.get(), "attachment"));
    }

    public static <T> boolean has(net.minecraft.world.entity.Entity entity, Supplier<AttachmentType<T>> attachment) {
        return entity.hasData(Objects.requireNonNull(attachment.get(), "attachment"));
    }
}

