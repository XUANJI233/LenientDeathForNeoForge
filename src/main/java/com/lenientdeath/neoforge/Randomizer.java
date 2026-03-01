package com.lenientdeath.neoforge;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 随机保留器：对未被其他规则命中的物品，按基础概率 + 幸运修正随机判定保留数量。
 */
public class Randomizer {
    private Randomizer() {}

    public static final Randomizer INSTANCE = new Randomizer();

    /**
     * Get the chance that a player keeps an item, accounting for luck if needed.
     * @param player Player to test
     * @return Float value from 0 to 1 that an item should be kept.
     */
    public float getChanceToKeep(@Nullable Player player) {
        var config = Config.COMMON;
        if (!config.RANDOMIZER_ENABLED.get()) return 0f;
        float chance = config.RANDOMIZER_CHANCE.get() / 100f;
        if (player == null) return chance;

        float luck = player.getLuck();
        float luckAddFactor = config.LUCK_ADDITIVE.get() / 100f;
        float luckMultiFactor = config.LUCK_MULTIPLIER.get().floatValue();

        return Mth.clamp(chance * (1 + (luckMultiFactor * luck)) + (luckAddFactor * luck), 0f, 1f);
    }

    public @Nullable Integer howManyToKeep(ItemStack stack, Player player) {
        float chance = getChanceToKeep(player);

        if (chance == 1f) return stack.getCount();
        if (chance == 0f) return 0;

        int keepCount = 0;
        for (int i = 0; i < stack.getCount(); i++) {
            if (player.getRandom().nextFloat() < chance) {
                keepCount++;
            }
        }
        return keepCount;
    }
}