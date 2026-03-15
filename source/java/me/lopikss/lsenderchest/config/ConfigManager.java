package me.lopikss.lsenderchest.config;

import me.lopikss.lsenderchest.LsEnderChestPlugin;
import me.lopikss.lsenderchest.storage.StorageType;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

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
        return getConfig().getString("storage.sqlite.file", "lsenderchest.db");
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
        return getConfig().getString("storage.mysql.parameters", "?useSSL=false&autoReconnect=true");
    }

    public boolean isOnlineMode() {
        return getConfig().getBoolean("settings.online-mode", true);
    }

    public int getDefaultRows() {
        return clamp(getConfig().getInt("settings.default-rows", 1), 1, 6);
    }

    public String getMessage(String path, String fallback) {
        return getConfig().getString("messages." + path, fallback);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}