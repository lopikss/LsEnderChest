package me.lopikss.lsenderchest;

import me.lopikss.lsenderchest.command.EnderChestCommand;
import me.lopikss.lsenderchest.config.ConfigManager;
import me.lopikss.lsenderchest.listener.ChestListener;
import me.lopikss.lsenderchest.listener.UpdateNotifyListener;
import me.lopikss.lsenderchest.manager.ChestManager;
import me.lopikss.lsenderchest.manager.PermissionManager;
import me.lopikss.lsenderchest.manager.PlayerIdManager;
import me.lopikss.lsenderchest.storage.StorageProvider;
import me.lopikss.lsenderchest.storage.StorageType;
import me.lopikss.lsenderchest.storage.mysql.MySQLStorage;
import me.lopikss.lsenderchest.storage.sqlite.SQLiteStorage;
import me.lopikss.lsenderchest.update.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LsEnderChestPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerIdManager playerIdManager;
    private PermissionManager permissionManager;
    private StorageProvider storageProvider;
    private ChestManager chestManager;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("config-guide.yml", false);

        this.updateChecker = new UpdateChecker(this);
        this.updateChecker.checkNow();

        this.configManager = new ConfigManager(this);
        this.playerIdManager = new PlayerIdManager(configManager);
        this.permissionManager = new PermissionManager(configManager);

        StorageType storageType = configManager.getStorageType();
        this.storageProvider = switch (storageType) {
            case SQLITE -> new SQLiteStorage(this, configManager);
            case MYSQL -> new MySQLStorage(this, configManager);
            default -> throw new IllegalStateException("Unsupported storage type: " + storageType);
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

        getServer().getPluginManager().registerEvents(new ChestListener(this, chestManager), this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this, updateChecker), this);

        getLogger().info("LsEnderChest enabled using " + storageType.name() + " storage.");
    }

    @Override
    public void onDisable() {
        if (storageProvider != null) {
            storageProvider.close();
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChestManager getChestManager() {
        return chestManager;
    }
}