package com.lenientdeath.neoforge.compat;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosCapability;

/**
 * Curios 模组兼容层：检测物品是否为 Curios 饰品。
 */
@SuppressWarnings("null") // Minecraft API 的 @Nullable 注解误报
public class CuriosCompat {
    private CuriosCompat() {}

    /**
     * 检查物品是否拥有 Curios ITEM Capability。
     * <p>
     * 内部已做模组加载检查，可安全调用。
     */
    public static boolean isCurio(ItemStack stack) {
        if (!ModList.get().isLoaded("curios")) return false;
        return stack.getCapability(CuriosCapability.ITEM) != null;
    }

    /** 预留扩展入口：当前 Curios API 无额外注册需求。 */
    public static void setup() {
    }
}