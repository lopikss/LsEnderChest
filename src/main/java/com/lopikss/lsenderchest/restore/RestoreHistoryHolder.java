package com.lopikss.lsenderchest.restore;

import com.lopikss.lsenderchest.log.LogManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class RestoreHistoryHolder implements InventoryHolder {
    final PlayerIdManager.PlayerIdentity target;
    final List<LogManager.RestorePoint> points;
    final int page;
    Inventory inventory;

    RestoreHistoryHolder(PlayerIdManager.PlayerIdentity target, List<LogManager.RestorePoint> points, int page) {
        this.target = target;
        this.points = points;
        this.page = page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
