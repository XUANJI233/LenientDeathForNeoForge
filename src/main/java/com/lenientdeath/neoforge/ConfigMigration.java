package com.lenientdeath.neoforge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置迁移：在服务器启动时，将旧版全局配置 (lenientdeath-common.toml)
 * 自动迁移到每世界独立配置 (serverconfig/lenientdeath-server.toml)。
 */
public final class ConfigMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger("LenientDeath/ConfigMigration");

    private ConfigMigration() {
    }

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        migrateLegacyCommonToWorldServerConfig(event.getServer());
    }

    private static void migrateLegacyCommonToWorldServerConfig(MinecraftServer server) {
        Path worldServerConfig = server.getServerDirectory().resolve("serverconfig").resolve("lenientdeath-server.toml");
        if (Files.exists(worldServerConfig)) {
            return;
        }

        Path legacyCommonConfig = FMLPaths.CONFIGDIR.get().resolve("lenientdeath-common.toml");
        if (!Files.exists(legacyCommonConfig)) {
            return;
        }

        try {
            Files.createDirectories(worldServerConfig.getParent());
            Files.copy(legacyCommonConfig, worldServerConfig);

            boolean applied = ConfigCommands.reloadFromPath(worldServerConfig);
            if (applied) {
                LOGGER.info("Migrated legacy config {} -> {} and applied immediately", legacyCommonConfig, worldServerConfig);
            } else {
                LOGGER.warn("Migrated legacy config {} -> {}, but failed to apply immediately", legacyCommonConfig, worldServerConfig);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to migrate legacy config {} -> {}", legacyCommonConfig, worldServerConfig, ex);
        }
    }
}
