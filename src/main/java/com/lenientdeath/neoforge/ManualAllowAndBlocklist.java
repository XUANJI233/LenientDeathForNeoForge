package com.lenientdeath.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack; // shouldKeep() 参数类型
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手动配置的始终保留/始终掉落物品列表（按 Item ID 和 Tag 筛选）。
 */
@SuppressWarnings("null") // Minecraft API 的 @Nullable 注解误报
public class ManualAllowAndBlocklist {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/Item Filtering");
    public static final ManualAllowAndBlocklist INSTANCE = new ManualAllowAndBlocklist();
    private ManualAllowAndBlocklist() {}

    private final Set<Item> alwaysPreserved = new HashSet<>();
    private final Set<Item> alwaysDroppedItems = new HashSet<>();
    private final Set<TagKey<Item>> alwaysPreservedTags = new HashSet<>();
    private final Set<TagKey<Item>> alwaysDroppedTags = new HashSet<>();

    public void setup() {
        // 配置值仅在 ModConfig 加载后可读取。
        // 实际刷新由 LenientDeathNeoForge 的配置事件监听触发。
    }

    protected @Nullable Boolean shouldKeep(ItemStack stack) {
        if (alwaysDroppedItems.contains(stack.getItem())) return false;
        for (TagKey<Item> tag : alwaysDroppedTags) {
            if (stack.is(tag)) return false;
        }
        if (alwaysPreserved.contains(stack.getItem())) return true;
        for (TagKey<Item> tag : alwaysPreservedTags) {
            if (stack.is(tag)) return true;
        }
        return null;
    }

    /**
     * Load items from the current tag set and config.
     * <p>
     * 防御性守卫：当 SERVER 配置尚未绑定时（如创建新世界阶段），
     * 跳过刷新以避免 {@code ConfigValue.get()} 触发 {@code checkState} 崩溃。
     */
    public void refreshItems() {
        if (!Config.SPEC.isLoaded()) {
            LOGGER.debug("Config not yet loaded, skipping refreshItems()");
            return;
        }

        this.alwaysPreserved.clear();
        this.alwaysDroppedItems.clear();
        this.alwaysPreservedTags.clear();
        this.alwaysDroppedTags.clear();

        LOGGER.debug("Creating always preserved list");

        List<? extends String> alwaysPreservedItems = Config.COMMON.ALWAYS_PRESERVED_ITEMS.get();
        List<? extends String> alwaysPreservedTags = Config.COMMON.ALWAYS_PRESERVED_TAGS.get();

        for (String itemId : alwaysPreservedItems) {
            try {
                ResourceLocation id = ResourceLocation.parse(itemId);
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == Items.AIR) {
                    LOGGER.warn("Unknown item ID: {}", itemId);
                    continue;
                }
                LOGGER.debug("Adding item {}", itemId);
                this.alwaysPreserved.add(item);
            } catch (Exception e) {
                LOGGER.warn("Invalid item ID: {}", itemId);
            }
        }

        for (String tagStr : alwaysPreservedTags) {
            try {
                TagKey<Item> tagKey = TagKey.create(BuiltInRegistries.ITEM.key(), ResourceLocation.parse(tagStr));
                this.alwaysPreservedTags.add(tagKey);
                LOGGER.debug("Adding tag {}", tagStr);
            } catch (Exception e) {
                LOGGER.warn("Invalid tag ID: {}", tagStr);
            }
        }

        LOGGER.debug("Total for always preserved: items={}, tags={}", this.alwaysPreserved.size(), this.alwaysPreservedTags.size());

        LOGGER.debug("Creating always dropped list");

        List<? extends String> alwaysDroppedItems = Config.COMMON.ALWAYS_DROPPED_ITEMS.get();
        List<? extends String> alwaysDroppedTags = Config.COMMON.ALWAYS_DROPPED_TAGS.get();

        for (String itemId : alwaysDroppedItems) {
            try {
                ResourceLocation id = ResourceLocation.parse(itemId);
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == Items.AIR) {
                    LOGGER.warn("Unknown item ID: {}", itemId);
                    continue;
                }
                LOGGER.debug("Adding item {}", itemId);
                this.alwaysDroppedItems.add(item);
            } catch (Exception e) {
                LOGGER.warn("Invalid item ID: {}", itemId);
            }
        }

        for (String tagStr : alwaysDroppedTags) {
            try {
                TagKey<Item> tagKey = TagKey.create(BuiltInRegistries.ITEM.key(), ResourceLocation.parse(tagStr));
                this.alwaysDroppedTags.add(tagKey);
                LOGGER.debug("Adding tag {}", tagStr);
            } catch (Exception e) {
                LOGGER.warn("Invalid tag ID: {}", tagStr);
            }
        }

        LOGGER.debug("Total for always dropped: items={}, tags={}", this.alwaysDroppedItems.size(), this.alwaysDroppedTags.size());
    }
}
