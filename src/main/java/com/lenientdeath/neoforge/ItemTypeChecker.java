package com.lenientdeath.neoforge;

import com.lenientdeath.neoforge.compat.CuriosCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * 按物品类型（装备、武器、工具等）判断保留/掉落策略。
 */
@SuppressWarnings("null") // Minecraft API 的 @Nullable 注解误报
public class ItemTypeChecker {
    public static final ItemTypeChecker INSTANCE = new ItemTypeChecker();
    private ItemTypeChecker() {}

    /**
     * 根据物品类型配置判断是否保留。
     *
     * @param player 玩家实例（当前未使用，保留用于未来扩展如个性化规则）
     * @param stack  待检查物品
     * @return {@code true} 保留，{@code false} 掉落，{@code null} 未命中任何规则
     */
    @SuppressWarnings("unused") // player 参数保留用于未来扩展
    public @Nullable Boolean shouldKeep(@Nullable Player player, ItemStack stack) {
        // 1. 检查开关
        if (!Config.COMMON.BY_ITEM_TYPE_ENABLED.get()) return null;

        Item item = stack.getItem();
        Config.TypeBehavior result = Config.TypeBehavior.IGNORE;

        // 2. 装备类
        if (item instanceof ArmorItem armor) {
            result = result.and(switch (armor.getType()) {
                case HELMET -> Config.COMMON.HELMETS.get();
                case CHESTPLATE -> Config.COMMON.CHESTPLATES.get();
                case LEGGINGS -> Config.COMMON.LEGGINGS.get();
                case BOOTS -> Config.COMMON.BOOTS.get();
                default -> Config.TypeBehavior.IGNORE; // Body armor 等
            });
        }
        if (item instanceof ElytraItem) result = result.and(Config.COMMON.ELYTRAS.get());
        if (item instanceof ShieldItem) result = result.and(Config.COMMON.SHIELDS.get());

        // 3. 饰品 (Curios)
        if (ModList.get().isLoaded("curios") && CuriosCompat.isCurio(stack)) {
            result = result.and(Config.COMMON.CURIOS.get());
        }

        // 4. 武器类
        boolean meleeWeapon = item instanceof SwordItem || item instanceof TridentItem || item instanceof MaceItem;
        boolean rangedWeapon = item instanceof ProjectileWeaponItem;

        if (meleeWeapon || rangedWeapon) {
            result = result.and(Config.COMMON.WEAPONS.get());
        }
        if (meleeWeapon) {
            result = result.and(Config.COMMON.MELEE_WEAPONS.get());
        }
        if (rangedWeapon) {
            result = result.and(Config.COMMON.RANGED_WEAPONS.get());
        }

        // 5. 工具与功能类
        if (item instanceof DiggerItem) {
            result = result.and(Config.COMMON.TOOLS.get());
        }
        if (item instanceof ShearsItem || item instanceof FlintAndSteelItem) {
            result = result.and(Config.COMMON.UTILITY_TOOLS.get());
        }
        if (item instanceof FishingRodItem) {
            result = result.and(Config.COMMON.FISHING_RODS.get());
        }
        if (item instanceof BucketItem || item == Items.MILK_BUCKET) {
            result = result.and(Config.COMMON.BUCKETS.get());
        }

        // 6. 其他常见分类
        if (item instanceof EnchantedBookItem) {
            result = result.and(Config.COMMON.ENCHANTED_BOOKS.get());
        }
        if (item == Items.TOTEM_OF_UNDYING) {
            result = result.and(Config.COMMON.TOTEMS.get());
        }
        if (item instanceof BlockItem) {
            result = result.and(Config.COMMON.BLOCK_ITEMS.get());
        }
        if (item instanceof SpawnEggItem) {
            result = result.and(Config.COMMON.SPAWN_EGGS.get());
        }
        if (item instanceof ArrowItem) {
            result = result.and(Config.COMMON.ARROWS.get());
        }

        // 7. 食物和药水
        if (stack.has(DataComponents.FOOD)) {
            result = result.and(Config.COMMON.FOOD.get());
        }
        if (item instanceof PotionItem) {
            result = result.and(Config.COMMON.POTIONS.get());
        }

        // 返回结果
        return switch (result) {
            case DROP -> false;
            case PRESERVE -> true;
            case IGNORE -> null;
        };
    }
}