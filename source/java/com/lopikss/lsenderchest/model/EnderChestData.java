package com.lopikss.lsenderchest.model;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class EnderChestData implements InventoryHolder {

    private final UUID ownerUuid;
    private final String ownerName;
    private final boolean adminView;
    private final ItemStack[] fullContents;
    private Inventory inventory;

    public EnderChestData(UUID ownerUuid, String ownerName, boolean adminView, ItemStack[] fullContents) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.adminView = adminView;
        this.fullContents = fullContents;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public boolean isAdminView() {
        return adminView;
    }

    public ItemStack[] getFullContents() {
        return fullContents;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}