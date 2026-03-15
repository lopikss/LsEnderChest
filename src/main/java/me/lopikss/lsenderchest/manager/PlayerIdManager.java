package me.lopikss.lsenderchest.manager;

import me.lopikss.lsenderchest.config.ConfigManager;
import org.bukkit.OfflinePlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PlayerIdManager {

    private final ConfigManager configManager;

    public PlayerIdManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public UUID getStorageUuid(OfflinePlayer player) {
        if (configManager.isOnlineMode()) {
            return player.getUniqueId();
        }

        return getOfflineModeUuid(player.getName() == null ? "unknown" : player.getName());
    }

    public UUID getStorageUuid(String playerName) {
        if (configManager.isOnlineMode()) {
            return null;
        }

        return getOfflineModeUuid(playerName);
    }

    public UUID getOfflineModeUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }
}