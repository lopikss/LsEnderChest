package com.lopikss.lsenderchest.restore;

import com.lopikss.lsenderchest.log.LogManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class RestorePreviewHolder implements InventoryHolder {
    final PlayerIdManager.PlayerIdentity target;
    final LogManager.RestorePoint point;
    final int historyPage;
    final int contentPage;
    Inventory inventory;

    RestorePreviewHolder(PlayerIdManager.PlayerIdentity target,
                         LogManager.RestorePoint point,
                         int historyPage,
                         int contentPage) {
        this.target = target;
        this.point = point;
        this.historyPage = historyPage;
        this.contentPage = contentPage;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
