package com.lenientdeath.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

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

        // 注册附件
        ModAttachments.register(modEventBus);

        // 注册运行时命令（服务端）
        NeoForge.EVENT_BUS.addListener(ConfigCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ConfigMigration::onServerAboutToStart);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            PreserveItems.INSTANCE.setup();
        });
    }
}