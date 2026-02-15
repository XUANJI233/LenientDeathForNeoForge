package com.lenientdeath.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles filtering of items for which should be dropped.
 */
@SuppressWarnings({"unused", "null"})
public class ManualAllowAndBlocklist {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/Item Filtering");
    public static final ManualAllowAndBlocklist INSTANCE = new ManualAllowAndBlocklist();
    private ManualAllowAndBlocklist() {}

    private final Set<Item> alwaysPreserved = new HashSet<>();
    private final Set<Item> alwaysDroppedItems = new HashSet<>();

    public void setup() {
        // 在模组初始化阶段加载一次配置和标签映射
        refreshItems();
    }

    protected @Nullable Boolean shouldKeep(ItemStack stack) {
        if (alwaysDroppedItems.contains(stack.getItem())) return false;
        if (alwaysPreserved.contains(stack.getItem())) return true;
        return null;
    }

    /**
     * Load items from the current tag set and config.
     */
    public void refreshItems() {
        this.alwaysPreserved.clear();
        this.alwaysDroppedItems.clear();

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
                // 检查物品是否具有这个标签
                for (Item item : BuiltInRegistries.ITEM) {
                    ItemStack stack = new ItemStack(item);
                    if (stack.is(tagKey)) {
                        this.alwaysPreserved.add(item);
                        LOGGER.debug("Adding tag {} item {}", tagStr, BuiltInRegistries.ITEM.getKey(item));
                    }
                }
                LOGGER.debug("Adding tag {}", tagStr);
            } catch (Exception e) {
                LOGGER.warn("Invalid tag ID: {}", tagStr);
            }
        }

        LOGGER.debug("Total for always preserved: {}", this.alwaysPreserved.size());

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
                // 检查物品是否具有这个标签
                for (Item item : BuiltInRegistries.ITEM) {
                    ItemStack stack = new ItemStack(item);
                    if (stack.is(tagKey)) {
                        this.alwaysDroppedItems.add(item);
                        LOGGER.debug("Adding tag {} item {}", tagStr, BuiltInRegistries.ITEM.getKey(item));
                    }
                }
                LOGGER.debug("Adding tag {}", tagStr);
            } catch (Exception e) {
                LOGGER.warn("Invalid tag ID: {}", tagStr);
            }
        }

        LOGGER.debug("Total for always dropped: {}", this.alwaysDroppedItems.size());
    }
}