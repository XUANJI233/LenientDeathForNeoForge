package com.lenientdeath.neoforge;

import com.lenientdeath.neoforge.compat.CuriosCompat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * 物品保留入口：判断死亡时应保留多少个物品。
 * <p>
 * 优先级：NBT 标记 → 手动列表 → 物品类型规则 → 随机保留。
 */
public class PreserveItems {
    public static final PreserveItems INSTANCE = new PreserveItems();

    private PreserveItems() {}

    public static int howManyToPreserve(Player player, ItemStack stack) {
        // 主开关：关闭时直接不保留
        if (Config.COMMON.PRESERVE_ITEMS_ENABLED.get()) {
            var test = INSTANCE.shouldPreserve(player, stack, false);
            if (test != null) return test;
        }
        return 0;
    }

    public void setup() {
        ManualAllowAndBlocklist.INSTANCE.setup();

        // Curios 兼容初始化（当前版本无额外注册逻辑，保留扩展点）
        if (ModList.get().isLoaded("curios")) {
            CuriosCompat.setup();
        }
    }

    public @Nullable Integer shouldPreserve(Player player, ItemStack stack, boolean skipRandom) {
        var nbtPreserveTest = NbtChecker.INSTANCE.shouldKeep(stack);
        if (nbtPreserveTest != null) return nbtPreserveTest ? stack.getCount() : 0;

        var configPreserveTest = ManualAllowAndBlocklist.INSTANCE.shouldKeep(stack);
        if (configPreserveTest != null) return configPreserveTest ? stack.getCount() : 0;

        var itemTypeTest = ItemTypeChecker.INSTANCE.shouldKeep(player, stack);
        if (itemTypeTest != null) return itemTypeTest ? stack.getCount() : 0;

        if (!skipRandom) return Randomizer.INSTANCE.howManyToKeep(stack, player);

        return null;
    }
}