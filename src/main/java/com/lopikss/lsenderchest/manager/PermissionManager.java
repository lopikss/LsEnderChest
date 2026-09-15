package com.lopikss.lsenderchest.manager;

import com.lopikss.lsenderchest.config.ConfigManager;
import org.bukkit.entity.Player;

public final class PermissionManager {

    private final ConfigManager config;

    public PermissionManager(ConfigManager config) {
        this.config = config;
    }

    public int getRows(Player player) {
        for (int rows = 6; rows >= 1; rows--) {
            if (player.hasPermission("enderchest.rows." + rows)) {
                return rows;
            }
        }
        return config.getDefaultRows();
    }
}
