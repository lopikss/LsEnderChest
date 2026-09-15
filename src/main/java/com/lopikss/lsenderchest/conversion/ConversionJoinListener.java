package com.lopikss.lsenderchest.conversion;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ConversionJoinListener implements Listener {

    private final LsEnderChestPlugin plugin;
    private final ConversionManager conversions;

    public ConversionJoinListener(LsEnderChestPlugin plugin, ConversionManager conversions) {
        this.plugin = plugin;
        this.conversions = conversions;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isConvertOnFirstJoin()) {
            return;
        }

        Player player = event.getPlayer();
        if (conversions.isConverted(player)) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || conversions.isConverted(player)) {
                return;
            }

            conversions.convert(player).thenAccept(result -> {
                switch (result) {
                    case CONVERTED -> plugin.getLogger().info("Migrated vanilla Ender Chest for " + player.getName() + ".");
                    case OLD_CONVERTER_DUPLICATE_CLEANED -> plugin.getLogger().info(
                            "Verified an old LsEnderChestConverter copy for " + player.getName()
                                    + " and safely cleared the duplicate vanilla contents.");
                    case CUSTOM_CHEST_NOT_EMPTY -> plugin.getLogger().warning(
                            "Skipped automatic vanilla Ender Chest migration for " + player.getName()
                                    + " because their LsEnderChest already contains different items."
                                    + " Use /lsec convert " + player.getName() + " after resolving the conflict.");
                    case VANILLA_CHANGED -> plugin.getLogger().warning(
                            "Cancelled Ender Chest migration for " + player.getName()
                                    + " because their vanilla contents changed during conversion.");
                    case STORAGE_ERROR -> plugin.getLogger().warning(
                            "Could not migrate vanilla Ender Chest for " + player.getName() + " because of a storage error.");
                    default -> {
                        // EMPTY, ALREADY_CONVERTED, BUSY and PLAYER_LEFT need no join-time message.
                    }
                }
            });
        }, 20L);
    }
}
