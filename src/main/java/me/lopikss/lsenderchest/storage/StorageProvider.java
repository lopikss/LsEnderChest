package me.lopikss.lsenderchest.storage;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public interface StorageProvider {

    void init();

    ItemStack[] load(UUID playerUuid);

    void save(UUID playerUuid, String playerName, ItemStack[] contents);

    void close();
}