package me.lopikss.lsenderchest.listener;

import me.lopikss.lsenderchest.LsEnderChestPlugin;
import me.lopikss.lsenderchest.manager.ChestManager;
import me.lopikss.lsenderchest.model.EnderChestData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.EnderChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestListener implements Listener {

    private final LsEnderChestPlugin plugin;
    private final ChestManager chestManager;
    private final Map<UUID, Location> openedEnderChestBlocks = new HashMap<>();

    public ChestListener(LsEnderChestPlugin plugin, ChestManager chestManager) {
        this.plugin = plugin;
        this.chestManager = chestManager;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnderChestData data)) {
            return;
        }

        chestManager.saveChest(data, event.getInventory());

        Player player = (Player) event.getPlayer();
        Location location = openedEnderChestBlocks.remove(player.getUniqueId());

        if (location == null) {
            return;
        }

        Block block = location.getBlock();
        if (block.getType() != Material.ENDER_CHEST) {
            return;
        }

        if (block.getState() instanceof EnderChest enderChest) {
            enderChest.close();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickEnderChest(PlayerInteractEvent event) {
        if (event.getAction().isLeftClick()) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.ENDER_CHEST) {
            return;
        }

        Player player = event.getPlayer();

        if (player.hasPermission("enderchest.bypass")) {
            return;
        }

        if (!chestManager.canUse(player)) {
            event.setCancelled(true);
            chestManager.sendNoPermission(player);
            return;
        }

        event.setCancelled(true);

        if (clicked.getState() instanceof EnderChest enderChest) {
            enderChest.open();
            openedEnderChestBlocks.put(player.getUniqueId(), clicked.getLocation());
        }

        chestManager.openOwnChest(player);
    }
}