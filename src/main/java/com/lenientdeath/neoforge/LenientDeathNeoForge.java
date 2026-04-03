package com.lenientdeath.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * LenientDeath 模组入口点：注册配置、附件、命令和事件监听。
 */
@Mod(LenientDeathNeoForge.MODID)
public class LenientDeathNeoForge {
    public static final String MODID = "lenientdeath";

    public LenientDeathNeoForge(IEventBus modEventBus) {
        // 注册配置
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, Config.SPEC, "lenientdeath-server.toml");

        // 注册初始化事件
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);

        // 注册附件
        ModAttachments.register(modEventBus);

        // 注册运行时命令（服务端）
        NeoForge.EVENT_BUS.addListener(ConfigCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ConfigMigration::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onTagsUpdated);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            PreserveItems.INSTANCE.setup();
        });
    }

    private void onConfigLoading(final ModConfigEvent.Loading event) {
        onConfigEvent(event.getConfig());
    }

    private void onConfigReloading(final ModConfigEvent.Reloading event) {
        onConfigEvent(event.getConfig());
    }

    private void onConfigEvent(final ModConfig modConfig) {
        if (modConfig.getType() != ModConfig.Type.SERVER) {
            return;
        }
        if (modConfig.getSpec() != Config.SPEC) {
            return;
        }
        ManualAllowAndBlocklist.INSTANCE.refreshItems();
        DeathEventHandler.onConfigLoaded();
    }

    /**
     * 数据包重载（/reload）后标签内容可能变化，刷新物品列表缓存。
     * <p>
     * shouldUpdateStaticData() 确保仅在服务端逻辑线程执行，
     * 避免单人游戏中客户端线程并发修改共享集合。
     * <p>
     * Config.SPEC.isLoaded() 守卫：创建新世界时 TagsUpdatedEvent 在
     * WorldLoader.load 阶段触发，此时 SERVER 配置尚未绑定，
     * 直接读取 ConfigValue 会导致 checkState 崩溃。
     */
    private void onTagsUpdated(final TagsUpdatedEvent event) {
        if (event.shouldUpdateStaticData() && Config.SPEC.isLoaded()) {
            ManualAllowAndBlocklist.INSTANCE.refreshItems();
        }
    }
}