package com.lopikss.lsenderchest.listener;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.model.EnderChestData;
import com.lopikss.lsenderchest.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.EnderChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
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

        if (event.getPlayer() instanceof Player player) {
            boolean readOnlyOtherChest = isReadOnlyOtherChest(player, data);

            if (!readOnlyOtherChest) {
                ItemStack[] contents = event.getInventory().getContents().clone();

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                        chestManager.saveChest(data, contents)
                );
            }

            Location location = openedEnderChestBlocks.remove(player.getUniqueId());
            if (location != null) {
                Block block = location.getBlock();
                if (block.getType() == Material.ENDER_CHEST && block.getState() instanceof EnderChest enderChest) {
                    enderChest.close();
                }
            }
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

        if (player.hasPermission("enderchest.bypass")) {
            return;
        }

        if (shouldAllowSneakPlacement(player, event.getItem())) {
            return;
        }

        Block enderChestBlock = resolveTargetEnderChest(clicked);
        if (enderChestBlock == null) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        if (hasBlockingBlockAbove(enderChestBlock)) {
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

        plugin.getLogManager().logOpenDetailed(
                player,
                player.getName(),
                "OWN",
                "BLOCK",
                enderChestBlock.getLocation()
        );

        chestManager.openOwnChest(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EnderChestData data)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        String ownerName = data.getOwnerName();
        Location location = player.getLocation();

        if (isReadOnlyOtherChest(player, data)) {
            plugin.getLogManager().logReadOnlyAttempt(
                    player,
                    ownerName,
                    event.getClick().name(),
                    event.getAction().name(),
                    location
            );
            event.setCancelled(true);
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null) {
            return;
        }

        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean clickedTopInventory = clickedInventory.equals(topInventory);

        if (event.isShiftClick()) {
            if (!clickedTopInventory && hasItem(currentItem)) {
                if (isBlocked(currentItem) && !player.hasPermission("enderchest.blocked.bypass")) {
                    logBlockedMove(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
                    cancelBlockedMove(event);
                    return;
                }

                logAdd(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
                return;
            }

            if (clickedTopInventory && hasItem(currentItem)) {
                logRemove(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
                return;
            }
        }

        if (clickedTopInventory && event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());

            if (isBlocked(hotbarItem) && !player.hasPermission("enderchest.blocked.bypass")) {
                logBlockedMove(player, ownerName, hotbarItem, event.getClick().name(), event.getAction().name(), location);
                cancelBlockedMove(event);
                return;
            }

            if (hasItem(currentItem)) {
                logRemove(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
            }

            if (hasItem(hotbarItem)) {
                logAdd(player, ownerName, hotbarItem, event.getClick().name(), event.getAction().name(), location);
            }
            return;
        }

        if (clickedTopInventory && hasItem(cursor)) {
            InventoryAction action = event.getAction();

            if (action == InventoryAction.PLACE_ALL
                    || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.PLACE_SOME
                    || action == InventoryAction.SWAP_WITH_CURSOR) {

                if (isBlocked(cursor) && !player.hasPermission("enderchest.blocked.bypass")) {
                    logBlockedMove(player, ownerName, cursor, event.getClick().name(), event.getAction().name(), location);
                    cancelBlockedMove(event);
                    return;
                }

                logAdd(player, ownerName, cursor, event.getClick().name(), event.getAction().name(), location);

                if (action == InventoryAction.SWAP_WITH_CURSOR && hasItem(currentItem)) {
                    logRemove(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
                }
                return;
            }
        }

        if (clickedTopInventory && hasItem(currentItem)) {
            InventoryAction action = event.getAction();

            if (action == InventoryAction.PICKUP_ALL
                    || action == InventoryAction.PICKUP_HALF
                    || action == InventoryAction.PICKUP_ONE
                    || action == InventoryAction.PICKUP_SOME
                    || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == InventoryAction.HOTBAR_SWAP
                    || action == InventoryAction.DROP_ALL_SLOT
                    || action == InventoryAction.DROP_ONE_SLOT) {

                logRemove(player, ownerName, currentItem, event.getClick().name(), event.getAction().name(), location);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EnderChestData data)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        String ownerName = data.getOwnerName();
        Location location = player.getLocation();

        if (isReadOnlyOtherChest(player, data)) {
            plugin.getLogManager().logReadOnlyAttempt(
                    player,
                    ownerName,
                    "DRAG",
                    "DRAG",
                    location
            );
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                ItemStack cursor = event.getOldCursor();

                if (isBlocked(cursor) && !player.hasPermission("enderchest.blocked.bypass")) {
                    plugin.getLogManager().logBlockedAttempt(
                            player,
                            ownerName,
                            cursor,
                            "DRAG",
                            "DRAG",
                            location
                    );

                    event.setCancelled(true);
                    sendBlockedMessage(player);
                    return;
                }

                if (hasItem(cursor)) {
                    logAdd(player, ownerName, cursor, "DRAG", "DRAG", location);
                }
                return;
            }
        }
    }

    private void logAdd(Player player,
                        String ownerName,
                        ItemStack item,
                        String clickType,
                        String inventoryAction,
                        Location location) {
        plugin.getLogManager().logAdd(player, item);
        plugin.getLogManager().logMoveDetailed(
                player,
                ownerName,
                item,
                "MOVE_IN",
                clickType,
                inventoryAction,
                location
        );
    }

    private void logRemove(Player player,
                           String ownerName,
                           ItemStack item,
                           String clickType,
                           String inventoryAction,
                           Location location) {
        plugin.getLogManager().logRemove(player, item);
        plugin.getLogManager().logRemoveDetailed(
                player,
                ownerName,
                item,
                clickType,
                inventoryAction,
                location
        );
    }

    private boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    private boolean isBlocked(ItemStack item) {
        return plugin.isBlockedItem(item);
    }

    private boolean isReadOnlyOtherChest(Player player, EnderChestData data) {
        return !data.isAdminView() && !player.getUniqueId().equals(data.getOwnerUuid());
    }

    private void logBlockedMove(Player player,
                                String ownerName,
                                ItemStack item,
                                String clickType,
                                String inventoryAction,
                                Location location) {
        plugin.getLogManager().logBlockedAttempt(
                player,
                ownerName,
                item,
                clickType,
                inventoryAction,
                location
        );
    }

    private void cancelBlockedMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        event.setCancelled(true);
        sendBlockedMessage(player);
    }

    private void sendBlockedMessage(Player player) {
        String message = plugin.getConfigManager().getMessage(
                "blocked-item",
                "&cThat item is not allowed in your Ender Chest."
        );

        player.sendMessage(ColorUtil.color(message));
    }

    private Block resolveTargetEnderChest(Block clicked) {
        if (clicked.getType() == Material.ENDER_CHEST) {
            return clicked;
        }

        Block below = clicked.getRelative(BlockFace.DOWN);
        if (below.getType() != Material.ENDER_CHEST) {
            return null;
        }

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

        if (above.getType().isOccluding()) {
            return true;
        }

        BlockData blockData = above.getBlockData();
        if (blockData instanceof Slab slab) {
            return slab.getType() == Slab.Type.DOUBLE;
        }

        return false;
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