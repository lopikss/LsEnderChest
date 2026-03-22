package com.lopikss.lsenderchest;

import com.lopikss.lsenderchest.command.EnderChestCommand;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.listener.ChestListener;
import com.lopikss.lsenderchest.log.LogManager;
import com.lopikss.lsenderchest.update.UpdateNotifyListener;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.manager.PermissionManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import com.lopikss.lsenderchest.storage.StorageProvider;
import com.lopikss.lsenderchest.storage.StorageType;
import com.lopikss.lsenderchest.storage.mysql.MySQLStorage;
import com.lopikss.lsenderchest.storage.sqlite.SQLiteStorage;
import com.lopikss.lsenderchest.update.UpdateChecker;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class LsEnderChestPlugin extends JavaPlugin {

    private enum RestrictionMode {
        BLACKLIST,
        WHITELIST
    }

    private ConfigManager configManager;
    private PlayerIdManager playerIdManager;
    private PermissionManager permissionManager;
    private StorageProvider storageProvider;
    private ChestManager chestManager;
    private UpdateChecker updateChecker;
    private ChestListener chestListener;
    private LogManager logManager;

    private final Set<Material> blockedMaterials = new HashSet<>();
    private final Set<String> blockedLore = new HashSet<>();
    private final Set<String> blockedNbt = new HashSet<>();

    private RestrictionMode restrictionMode = RestrictionMode.BLACKLIST;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("config-guide.yml", false);
        saveResource("blocked-items.yml", false);

        addMissingConfigValues();

        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.checkNow();

        this.configManager = new ConfigManager(this);
        this.playerIdManager = new PlayerIdManager(configManager);
        this.permissionManager = new PermissionManager(configManager);
        this.logManager = new LogManager(this);

        loadBlockedItems();

        StorageType storageType = configManager.getStorageType();
        this.storageProvider = switch (storageType) {
            case SQLITE -> new SQLiteStorage(this, configManager);
            case MYSQL -> new MySQLStorage(this, configManager);
        };

        this.storageProvider.init();

        this.chestManager = new ChestManager(
                this,
                configManager,
                playerIdManager,
                permissionManager,
                storageProvider
        );

        EnderChestCommand command = new EnderChestCommand(this, chestManager);
        PluginCommand lsecCommand = getCommand("lsec");
        if (lsecCommand != null) {
            lsecCommand.setExecutor(command);
            lsecCommand.setTabCompleter(command);
        }

        this.chestListener = new ChestListener(this, chestManager);
        getServer().getPluginManager().registerEvents(chestListener, this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this, updateChecker), this);

        getLogger().info("LsEnderChest enabled using " + storageType.name() + " storage.");
    }

    @Override
    public void onDisable() {
        if (chestListener != null) {
            chestListener.shutdown();
        }

        if (storageProvider != null) {
            storageProvider.close();
        }
    }

    private void addMissingConfigValues() {
        FileConfiguration config = getConfig();
        boolean changed = false;

        if (!config.contains("messages.blocked-item")) {
            config.set("messages.blocked-item", "&cThat item is not allowed in your Ender Chest.");
            changed = true;
        }

        if (!config.contains("logging")) {
            config.set("logging", true);
            changed = true;
        }

        if (changed) {
            saveConfig();
        }
    }

    private void loadBlockedItems() {
        File file = new File(getDataFolder(), "blocked-items.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        blockedMaterials.clear();
        blockedLore.clear();
        blockedNbt.clear();

        String modeValue = config.getString("mode", "BLACKLIST").toUpperCase(Locale.ROOT);
        try {
            restrictionMode = RestrictionMode.valueOf(modeValue);
        } catch (IllegalArgumentException ignored) {
            restrictionMode = RestrictionMode.BLACKLIST;
            getLogger().warning("Invalid mode in blocked-items.yml: " + modeValue + ". Using BLACKLIST.");
        }

        for (String materialName : config.getStringList("blocked-items")) {
            try {
                blockedMaterials.add(Material.valueOf(materialName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid material in blocked-items.yml: " + materialName);
            }
        }

        for (String line : config.getStringList("blocked-lore")) {
            String normalized = normalize(line);
            if (!normalized.isEmpty()) {
                blockedLore.add(normalized);
            }
        }

        for (String value : config.getStringList("blocked-nbt")) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                blockedNbt.add(normalized);
            }
        }
    }

    public void reloadBlockedItems() {
        loadBlockedItems();
    }

    public boolean isBlockedItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return restrictionMode == RestrictionMode.WHITELIST;
        }

        boolean matches = matchesRestriction(item);
        return restrictionMode == RestrictionMode.BLACKLIST ? matches : !matches;
    }

    private boolean matchesRestriction(ItemStack item) {
        if (blockedMaterials.contains(item.getType())) {
            return true;
        }

        if (!item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (blockedLore.contains(normalize(line))) {
                    return true;
                }
            }
        }

        if (blockedNbt.contains("custommodeldata") && meta.hasCustomModelData()) {
            return true;
        }

        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChestManager getChestManager() {
        return chestManager;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}