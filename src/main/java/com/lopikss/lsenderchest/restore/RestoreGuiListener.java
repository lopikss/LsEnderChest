package com.lopikss.lsenderchest.restore;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.log.LogManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class RestoreGuiListener implements Listener {

    private final LsEnderChestPlugin plugin;
    private final RestoreManager restores;

    public RestoreGuiListener(LsEnderChestPlugin plugin, RestoreManager restores) {
        this.plugin = plugin;
        this.restores = restores;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        boolean restoreGui = top.getHolder() instanceof RestoreHistoryHolder
                || top.getHolder() instanceof RestorePreviewHolder
                || top.getHolder() instanceof RestoreConfirmHolder;
        if (restoreGui && !player.hasPermission("enderchest.admin")) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        if (top.getHolder() instanceof RestoreHistoryHolder holder) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
                return;
            }
            if (event.getRawSlot() == 45 && holder.page > 0) {
                restores.openHistoryPage(player, holder.target, holder.points, holder.page - 1);
                return;
            }
            if (event.getRawSlot() == 53 && (holder.page + 1) * 45 < holder.points.size()) {
                restores.openHistoryPage(player, holder.target, holder.points, holder.page + 1);
                return;
            }
            if (event.getRawSlot() >= 45) {
                return;
            }
            int index = holder.page * 45 + event.getRawSlot();
            if (index < 0 || index >= holder.points.size()) {
                return;
            }
            LogManager.RestorePoint point = holder.points.get(index);
            if (event.isRightClick()) {
                restores.openConfirm(player, holder.target, point, holder.page);
            } else {
                restores.openPreview(player, holder.target, point, holder.page, 0);
            }
            return;
        }

        if (top.getHolder() instanceof RestorePreviewHolder holder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) {
                if (holder.contentPage > 0) {
                    restores.openPreview(player, holder.target, holder.point, holder.historyPage, holder.contentPage - 1);
                } else {
                    plugin.getLogManager().loadRestorePoints(holder.target.uuid())
                            .thenAccept(points -> plugin.getServer().getScheduler().runTask(plugin,
                                    () -> restores.openHistoryPage(player, holder.target, points, holder.historyPage)));
                }
            } else if (event.getRawSlot() == 49) {
                restores.openConfirm(player, holder.target, holder.point, holder.historyPage);
            } else if (event.getRawSlot() == 53) {
                restores.openPreview(player, holder.target, holder.point, holder.historyPage, holder.contentPage + 1);
            }
            return;
        }

        if (top.getHolder() instanceof RestoreConfirmHolder holder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) {
                restores.restore(player, holder.target, holder.point);
            } else if (event.getRawSlot() == 15) {
                plugin.getLogManager().loadRestorePoints(holder.target.uuid())
                        .thenAccept(points -> plugin.getServer().getScheduler().runTask(plugin,
                                () -> restores.openHistoryPage(player, holder.target, points, holder.historyPage)));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof RestoreHistoryHolder
                || top.getHolder() instanceof RestorePreviewHolder
                || top.getHolder() instanceof RestoreConfirmHolder) {
            event.setCancelled(true);
        }
    }
}
