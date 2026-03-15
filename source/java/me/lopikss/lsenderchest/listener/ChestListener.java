package me.lopikss.lsenderchest.listener;

import me.lopikss.lsenderchest.LsEnderChestPlugin;
import me.lopikss.lsenderchest.manager.ChestManager;
import me.lopikss.lsenderchest.model.EnderChestData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.EnderChest;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestListener implements Listener {

    private static final double MAX_OPEN_DISTANCE_SQUARED = 64.0D;

    private final LsEnderChestPlugin plugin;
    private final ChestManager chestManager;
    private final Map<UUID, Location> openedEnderChestBlocks = new HashMap<>();
    private final BukkitTask distanceCheckTask;

    public ChestListener(LsEnderChestPlugin plugin, ChestManager chestManager) {
        this.plugin = plugin;
        this.chestManager = chestManager;
        this.distanceCheckTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::checkOpenChestDistances,
                10L,
                10L
        );
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnderChestData data)) {
            return;
        }

        chestManager.saveChest(data, event.getInventory());

        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRightClickEnderChest(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Player player = event.getPlayer();

        // Allow normal sneak-place behavior with blocks
        if (shouldAllowSneakPlacement(player, event.getItem())) {
            return;
        }

        Block enderChestBlock = resolveTargetEnderChest(clicked);
        if (enderChestBlock == null) {
            return;
        }

        // Always stop vanilla behavior first
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        // Match vanilla behavior: a fully blocking block directly above prevents opening
        if (hasBlockingBlockAbove(enderChestBlock)) {
            return;
        }

        if (player.hasPermission("enderchest.bypass")) {
            return;
        }

        if (!chestManager.canUse(player)) {
            chestManager.sendNoPermission(player);
            return;
        }

        if (enderChestBlock.getState() instanceof EnderChest enderChest) {
            enderChest.open();
            openedEnderChestBlocks.put(player.getUniqueId(), enderChestBlock.getLocation());
        }

        chestManager.openOwnChest(player);
    }

    private Block resolveTargetEnderChest(Block clicked) {
        if (clicked.getType() == Material.ENDER_CHEST) {
            return clicked;
        }

        Block below = clicked.getRelative(BlockFace.DOWN);
        if (below.getType() != Material.ENDER_CHEST) {
            return null;
        }

        // If the clicked block is directly above an Ender Chest and is not a full blocking block,
        // treat it as an interaction with the Ender Chest.
        if (!clicked.getType().isOccluding()) {
            return below;
        }

        return null;
    }

    private boolean shouldAllowSneakPlacement(Player player, ItemStack itemInHand) {
        return player.isSneaking()
                && itemInHand != null
                && itemInHand.getType().isBlock();
    }

    private boolean hasBlockingBlockAbove(Block enderChestBlock) {
        Block above = enderChestBlock.getRelative(BlockFace.UP);
        return above.getType().isOccluding();
    }

    private void checkOpenChestDistances() {
        for (Map.Entry<UUID, Location> entry : new HashMap<>(openedEnderChestBlocks).entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                openedEnderChestBlocks.remove(entry.getKey());
                continue;
            }

            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof EnderChestData)) {
                openedEnderChestBlocks.remove(entry.getKey());
                continue;
            }

            Location chestLocation = entry.getValue();
            Block block = chestLocation.getBlock();
            if (block.getType() != Material.ENDER_CHEST) {
                player.closeInventory();
                continue;
            }

            Location playerLocation = player.getLocation();
            if (playerLocation.getWorld() == null || !playerLocation.getWorld().equals(chestLocation.getWorld())) {
                player.closeInventory();
                continue;
            }

            Location chestCenter = chestLocation.clone().add(0.5D, 0.5D, 0.5D);
            if (playerLocation.distanceSquared(chestCenter) > MAX_OPEN_DISTANCE_SQUARED) {
                player.closeInventory();
            }
        }
    }

    public void shutdown() {
        distanceCheckTask.cancel();
    }
}