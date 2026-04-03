package com.lenientdeath.neoforge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 根据物品的 CustomData NBT 标记判断是否保留（类似灵魂绑定）。
 */
@SuppressWarnings("null") // Minecraft API 的 @Nullable 注解误报
public class NbtChecker {
    public static final NbtChecker INSTANCE = new NbtChecker();

    private NbtChecker() {}

    public @Nullable Boolean shouldKeep(ItemStack stack) {
        var config = Config.COMMON;
        if (!config.NBT_ENABLED.get()) return null;

        var tag = stack.get(DataComponents.CUSTOM_DATA);
        if (tag == null) return null;

        return tag.copyTag().getBoolean(config.NBT_KEY.get());
    }
}