package com.lopikss.lsenderchest.update;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotifyListener implements Listener {

    private final LsEnderChestPlugin plugin;
    private final UpdateChecker updateChecker;

    public UpdateNotifyListener(LsEnderChestPlugin plugin, UpdateChecker updateChecker) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp() && !player.hasPermission("enderchest.admin")) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> updateChecker.checkNow(false)
                .thenAccept(update -> {
                    if (update.isEmpty() || !plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            updateChecker.notifyPlayer(player, update.get());
                        }
                    });
                }), 40L);
    }
}
