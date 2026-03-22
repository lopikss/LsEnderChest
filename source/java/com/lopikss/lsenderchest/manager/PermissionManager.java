package com.lopikss.lsenderchest.manager;

import com.lopikss.lsenderchest.config.ConfigManager;
import org.bukkit.entity.Player;

public class PermissionManager {

    private final ConfigManager configManager;

    public PermissionManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public int getRows(Player player) {
        for (int rows = 6; rows >= 1; rows--) {
            if (player.hasPermission("enderchest.rows." + rows)) {
                return rows;
            }
        }
        return configManager.getDefaultRows();
    }
}