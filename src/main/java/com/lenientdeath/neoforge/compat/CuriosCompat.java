package com.lenientdeath.neoforge.compat;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosCapability;

@SuppressWarnings("null")
public class CuriosCompat {
    public static boolean isCurio(ItemStack stack) {
        // 如果没有加载 Curios 模组，直接返回 false
        if (!ModList.get().isLoaded("curios")) return false;

        // 【修正】使用 NeoForge 标准 Capability 系统
        // 旧方法 getCurio() 已过时，现在直接查询物品是否拥有 "ITEM" Capability
        // 在 NeoForge 1.21 中，如果有该 Capability 则返回对象，否则返回 null
        return stack.getCapability(CuriosCapability.ITEM) != null;
    }

    public static void setup() {
        // 预留扩展入口：当前 Curios API 无需额外注册。
    }
}