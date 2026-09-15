package com.lopikss.lsenderchest;

import com.lopikss.lsenderchest.command.EnderChestCommand;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.conversion.ConversionJoinListener;
import com.lopikss.lsenderchest.conversion.ConversionManager;
import com.lopikss.lsenderchest.conversion.ConvertedPlayersStore;
import com.lopikss.lsenderchest.listener.ChestListener;
import com.lopikss.lsenderchest.log.LogManager;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.manager.PermissionManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import com.lopikss.lsenderchest.manager.RowStateStore;
import com.lopikss.lsenderchest.overflow.OverflowListener;
import com.lopikss.lsenderchest.overflow.OverflowManager;
import com.lopikss.lsenderchest.restore.RestoreGuiListener;
import com.lopikss.lsenderchest.restore.RestoreManager;
import com.lopikss.lsenderchest.restriction.ItemRestrictionManager;
import com.lopikss.lsenderchest.storage.StorageProvider;
import com.lopikss.lsenderchest.storage.StorageService;
import com.lopikss.lsenderchest.storage.StorageType;
import com.lopikss.lsenderchest.storage.mysql.MySQLStorage;
import com.lopikss.lsenderchest.storage.sqlite.SQLiteStorage;
import com.lopikss.lsenderchest.update.UpdateChecker;
import com.lopikss.lsenderchest.update.UpdateNotifyListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public final class LsEnderChestPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private LogManager logManager;
    private ItemRestrictionManager restrictionManager;
    private StorageService storageService;
    private OverflowManager overflowManager;
    private ChestManager chestManager;
    private ChestListener chestListener;
    private ConversionManager conversionManager;
    private RestoreManager restoreManager;
    private UpdateChecker updateChecker;
    private StorageType startupStorageType;
    private String startupStorageSignature;
    private boolean startupOnlineMode;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledResource("config-guide.yml");
        saveBundledResource("blocked-items.yml");

        try {
            prepareInternalDataDirectory();
            saveBundledResource("EnderChest/README.txt");
            saveBundledResource("EnderChest/converted-players.yml");
        } catch (Exception exception) {
            getLogger().severe("LsEnderChest could not prepare its internal data folder: " + rootMessage(exception));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configManager = new ConfigManager(this);
        logManager = new LogManager(this);
        restrictionManager = new ItemRestrictionManager(this);
        restrictionManager.reload();

        try {
            startupStorageType = configManager.getStorageType();
            startupStorageSignature = storageSignature();
            startupOnlineMode = configManager.isOnlineMode();

            StorageProvider provider = switch (startupStorageType) {
                case SQLITE -> new SQLiteStorage(this, configManager);
                case MYSQL -> new MySQLStorage(configManager);
            };

            storageService = new StorageService(provider);
            storageService.initialize(Duration.ofSeconds(15));
        } catch (Exception exception) {
            getLogger().severe("LsEnderChest could not initialize storage: " + rootMessage(exception));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlayerIdManager playerIds = new PlayerIdManager(startupOnlineMode);
        PermissionManager permissions = new PermissionManager(configManager);
        RowStateStore rowState = new RowStateStore(this);
        overflowManager = new OverflowManager(this, storageService, playerIds);
        chestManager = new ChestManager(
                this, configManager, playerIds, permissions, storageService, overflowManager, rowState
        );

        ConvertedPlayersStore convertedPlayers = new ConvertedPlayersStore(this);
        conversionManager = new ConversionManager(this, chestManager, convertedPlayers);
        restoreManager = new RestoreManager(this, chestManager, overflowManager, logManager);

        EnderChestCommand command = new EnderChestCommand(this, chestManager, conversionManager, restoreManager);
        registerCommand("lsec", "Open and manage LsEnderChest storage.", command);
        registerCommand("ec", "Open and manage LsEnderChest storage.", command);
        registerCommand("enderchest", "Open and manage LsEnderChest storage.", command);

        chestListener = new ChestListener(this, chestManager, restrictionManager);
        getServer().getPluginManager().registerEvents(chestListener, this);
        getServer().getPluginManager().registerEvents(new OverflowListener(overflowManager), this);
        getServer().getPluginManager().registerEvents(new RestoreGuiListener(this, restoreManager), this);
        getServer().getPluginManager().registerEvents(new ConversionJoinListener(this, conversionManager), this);

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(new UpdateNotifyListener(this, updateChecker), this);
        updateChecker.checkNow(true);

        Plugin oldConverter = getServer().getPluginManager().getPlugin("LsEnderChestConverter");
        if (oldConverter != null) {
            if (oldConverter.isEnabled()) {
                getServer().getPluginManager().disablePlugin(oldConverter);
            }
            getLogger().warning("Disabled the old LsEnderChestConverter plugin because conversion is built into LsEnderChest 2.0.0. Remove the old converter JAR.");
        }

        getLogger().info("LsEnderChest " + getPluginMeta().getVersion()
                + " enabled using " + startupStorageType.name() + " storage.");
    }

    @Override
    public void onDisable() {
        if (chestListener != null) {
            chestListener.shutdown();
        }
        if (overflowManager != null) {
            overflowManager.shutdown();
        }
        if (chestManager != null) {
            chestManager.shutdown();
        }
        if (storageService != null) {
            storageService.shutdownAndFlush(Duration.ofSeconds(10));
        }
        if (logManager != null) {
            logManager.shutdown(Duration.ofSeconds(5));
        }
    }

    public boolean reloadPluginConfiguration() {
        configManager.reload();
        restrictionManager.reload();
        if (conversionManager != null) {
            conversionManager.getConvertedPlayers().reload();
        }

        boolean restartRequired = configManager.isOnlineMode() != startupOnlineMode;
        try {
            restartRequired |= !storageSignature().equals(startupStorageSignature);
        } catch (IllegalArgumentException exception) {
            restartRequired = true;
            getLogger().warning(exception.getMessage());
        }

        if (updateChecker != null && configManager.isUpdateCheckEnabled()) {
            updateChecker.checkNow(true);
        }
        return restartRequired;
    }

    private String storageSignature() {
        StorageType type = configManager.getStorageType();
        return switch (type) {
            case SQLITE -> "sqlite|" + configManager.getSQLiteFile();
            case MYSQL -> "mysql|"
                    + configManager.getMySQLHost() + "|"
                    + configManager.getMySQLPort() + "|"
                    + configManager.getMySQLDatabase() + "|"
                    + configManager.getMySQLUsername() + "|"
                    + configManager.getMySQLPassword() + "|"
                    + configManager.getMySQLParameters();
        };
    }

    public Path getInternalDataPath(String fileName) {
        return getDataFolder().toPath().resolve("EnderChest").resolve(fileName);
    }

    private void prepareInternalDataDirectory() {
        Path internalDirectory = getDataFolder().toPath().resolve("EnderChest");
        try {
            Files.createDirectories(internalDirectory);
            migrateLegacyInternalFile("converted-players.yml", internalDirectory);
            migrateLegacyInternalFile("row-state.yml", internalDirectory);
            migrateLegacyInternalFile("overflow-secret.key", internalDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare the LsEnderChest internal data folder.", exception);
        }
    }

    private void migrateLegacyInternalFile(String fileName, Path internalDirectory) throws IOException {
        Path legacy = getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(legacy)) {
            return;
        }

        Path target = internalDirectory.resolve(fileName);
        if (Files.exists(target)) {
            getLogger().warning("Found both legacy and new copies of " + fileName
                    + ". Keeping both files unchanged; verify them manually before deleting either copy.");
            return;
        }

        try {
            Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(legacy, target);
        }

        getLogger().info("Moved " + fileName + " into EnderChest/ internal storage.");
    }

    private void saveBundledResource(String resource) {
        if (!getDataFolder().toPath().resolve(resource).toFile().exists()) {
            saveResource(resource, false);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public ChestManager getChestManager() {
        return chestManager;
    }

    public ConversionManager getConversionManager() {
        return conversionManager;
    }

    public RestoreManager getRestoreManager() {
        return restoreManager;
    }

    public OverflowManager getOverflowManager() {
        return overflowManager;
    }
}
