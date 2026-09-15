package com.lopikss.lsenderchest.overflow;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class OverflowViewHolder implements InventoryHolder {

    private final OverflowSession session;
    private final int page;
    private Inventory inventory;

    OverflowViewHolder(OverflowSession session, int page) {
        this.session = session;
        this.page = page;
    }

    OverflowSession session() {
        return session;
    }

    int page() {
        return page;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
