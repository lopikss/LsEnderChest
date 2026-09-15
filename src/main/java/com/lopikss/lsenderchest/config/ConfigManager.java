package com.lopikss.lsenderchest.config;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.storage.StorageType;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class ConfigManager {

    private final LsEnderChestPlugin plugin;

    public ConfigManager(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
    }

    public FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public StorageType getStorageType() {
        return StorageType.fromString(getConfig().getString("storage.type", "sqlite"));
    }

    public String getSQLiteFile() {
        return getConfig().getString("storage.sqlite.file", "EnderChest/database.db");
    }

    public String getMySQLHost() {
        return getConfig().getString("storage.mysql.host", "localhost");
    }

    public int getMySQLPort() {
        return getConfig().getInt("storage.mysql.port", 3306);
    }

    public String getMySQLDatabase() {
        return getConfig().getString("storage.mysql.database", "lsenderchest");
    }

    public String getMySQLUsername() {
        return getConfig().getString("storage.mysql.username", "user");
    }

    public String getMySQLPassword() {
        return getConfig().getString("storage.mysql.password", "password");
    }

    public String getMySQLParameters() {
        String parameters = getConfig().getString("storage.mysql.parameters", "?useSSL=false&autoReconnect=true");
        if (parameters == null || parameters.isBlank()) {
            return "";
        }
        return parameters.startsWith("?") ? parameters : "?" + parameters;
    }

    public boolean isOnlineMode() {
        return getConfig().getBoolean("settings.online-mode", true);
    }

    public int getDefaultRows() {
        return Math.max(1, Math.min(6, getConfig().getInt("settings.default-rows", 1)));
    }

    public boolean includeOfflinePlayersInTabComplete() {
        return getConfig().getBoolean("settings.tab-complete-offline-players", true);
    }

    public boolean isConvertOnFirstJoin() {
        return getConfig().getBoolean("conversion.convert-on-first-join", true);
    }

    public boolean isLoggingEnabled() {
        return getConfig().getBoolean("logging.enabled", true);
    }

    public int getRestoreHistoryLimit() {
        return Math.max(10, Math.min(500, getConfig().getInt("restore.max-history", 100)));
    }

    public List<String> getOverflowChestLore() {
        List<String> configured = getConfig().getStringList("overflow-chest.lore");
        if (!configured.isEmpty()) {
            return configured;
        }
        return List.of(
                "&7Extra items that no longer fit in your Ender Chest.",
                "",
                "&fItems: &e%items%",
                "&fCreated: &7%created%",
                "",
                "&bRight-click to release all items",
                "&8Contents will be dropped on the ground.",
                "&8One-time use • Cannot be placed"
        );
    }

    public long getPermissionMonitorTicks() {
        return Math.max(20L, getConfig().getLong("settings.permission-monitor-ticks", 40L));
    }

    public int getConfirmationTimeoutSeconds() {
        return Math.max(5, Math.min(120, getConfig().getInt("settings.confirmation-timeout-seconds", 30)));
    }

    public boolean isUpdateCheckEnabled() {
        return getConfig().getBoolean("updates.enabled", true);
    }

    public boolean shouldNotifyAdminsAboutUpdates() {
        return getConfig().getBoolean("updates.notify-admins", true);
    }

    public String getChestTitle(String ownerName) {
        String fallback = getConfig().getString("title.other", "%player%'s Ender Chest");
        String configured = getConfig().getString("title.chest", fallback);
        return (configured == null ? "%player%'s Ender Chest" : configured)
                .replace("%player%", ownerName);
    }

    public String getMessage(String key, String fallback) {
        return getConfig().getString("messages." + key, fallback);
    }
}
