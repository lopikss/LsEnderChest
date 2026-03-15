package me.lopikss.lsenderchest.manager;

import me.lopikss.lsenderchest.LsEnderChestPlugin;
import me.lopikss.lsenderchest.config.ConfigManager;
import me.lopikss.lsenderchest.model.EnderChestData;
import me.lopikss.lsenderchest.storage.StorageProvider;
import me.lopikss.lsenderchest.util.ColorUtil;
import me.lopikss.lsenderchest.util.ItemStackUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ChestManager {

    private final LsEnderChestPlugin plugin;
    private final ConfigManager configManager;
    private final PlayerIdManager playerIdManager;
    private final PermissionManager permissionManager;
    private final StorageProvider storageProvider;

    public ChestManager(LsEnderChestPlugin plugin,
                        ConfigManager configManager,
                        PlayerIdManager playerIdManager,
                        PermissionManager permissionManager,
                        StorageProvider storageProvider) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.playerIdManager = playerIdManager;
        this.permissionManager = permissionManager;
        this.storageProvider = storageProvider;
    }

    public void openOwnChest(Player viewer) {
        int rows = permissionManager.getRows(viewer);
        openChest(viewer, viewer, rows, false);
    }

    public void openOtherChest(Player viewer, OfflinePlayer target) {
        openChest(viewer, target, 6, true);
    }

    private void openChest(Player viewer, OfflinePlayer target, int rows, boolean adminView) {
        UUID storageUuid = playerIdManager.getStorageUuid(target);
        String ownerName = target.getName() == null ? "unknown" : target.getName();

        ItemStack[] fullContents = storageProvider.load(storageUuid);
        ItemStack[] safeFullContents = ItemStackUtil.cloneArray(fullContents, 54);

        EnderChestData holder = new EnderChestData(storageUuid, ownerName, adminView, safeFullContents);

        String title = adminView
                ? ownerName + "'s Ender Chest"
                : "Ender Chest";

        Inventory inventory = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inventory);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, safeFullContents[i]);
        }

        viewer.openInventory(inventory);
    }

    public void saveChest(EnderChestData data, Inventory visibleInventory) {
        ItemStack[] fullContents = data.getFullContents();

        for (int i = 0; i < visibleInventory.getSize(); i++) {
            fullContents[i] = visibleInventory.getItem(i) == null ? null : visibleInventory.getItem(i).clone();
        }

        storageProvider.save(data.getOwnerUuid(), data.getOwnerName(), fullContents);
    }

    public boolean canUse(Player player) {
        return player.hasPermission("enderchest.use");
    }

    public boolean canAdmin(Player player) {
        return player.hasPermission("enderchest.admin");
    }

    public void sendNoPermission(Player player) {
        player.sendMessage(ColorUtil.color(configManager.getMessage("no-permission", "&cYou do not have permission.")));
    }

    public void sendPlayerNotFound(Player player) {
        player.sendMessage(ColorUtil.color(configManager.getMessage("player-not-found", "&cPlayer not found.")));
    }

    public void sendOpenedOther(Player player, String targetName) {
        player.sendMessage(ColorUtil.color(
                configManager.getMessage("opened-other", "&aOpened %player%'s ender chest.")
                        .replace("%player%", targetName)
        ));
    }

    public OfflinePlayer findTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        if (!configManager.isOnlineMode()) {
            return Bukkit.getOfflinePlayer(name);
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            return offline;
        }

        return null;
    }

    public void reload() {
        configManager.reload();
    }
}