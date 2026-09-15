package com.lopikss.lsenderchest.listener;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.model.EnderChestData;
import com.lopikss.lsenderchest.restriction.ItemRestrictionManager;
import com.lopikss.lsenderchest.util.ItemStackUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.EnderChest;
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

public final class ChestListener implements Listener {

    private static final double MAX_OPEN_DISTANCE_SQUARED = 64.0;

    private final LsEnderChestPlugin plugin;
    private final ChestManager chests;
    private final ItemRestrictionManager restrictions;
    private final Map<UUID, BlockKey> physicalChestByViewer = new HashMap<>();
    private final Map<BlockKey, Integer> physicalChestViewers = new HashMap<>();
    private final BukkitTask distanceTask;

    public ChestListener(LsEnderChestPlugin plugin,
                         ChestManager chests,
                         ItemRestrictionManager restrictions) {
        this.plugin = plugin;
        this.chests = chests;
        this.restrictions = restrictions;
        this.distanceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkPhysicalChestDistance, 10L, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.ENDER_CHEST) {
            return;
        }

        Player player = event.getPlayer();
        if (chests.isConversionInProgress(player)) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            chests.sendConversionInProgress(player);
            return;
        }

        if (player.hasPermission("enderchest.bypass")) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (player.isSneaking() && mainHand.getType().isBlock()) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        if (!(clicked.getState() instanceof EnderChest enderChest) || enderChest.isBlocked()) {
            return;
        }

        if (!player.hasPermission("enderchest.use")) {
            chests.sendNoPermission(player);
            return;
        }

        chests.openOwnChest(player, "BLOCK", () -> trackPhysicalChest(player, clicked));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EnderChestData data) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        boolean affectsTop = affectsTopInventory(event, top);
        if (!chests.canEdit(player, data) && affectsTop) {
            event.setCancelled(true);
            plugin.getLogManager().logReadOnlyAttempt(
                    data.getOwnerUuid(), data.getOwnerName(), player, data.getCycleId(),
                    event.getClick().name(), event.getAction().name(), player.getLocation()
            );
            return;
        }

        if (affectsTop && !player.hasPermission("enderchest.blocked.bypass")) {
            ItemStack incoming = findIncomingItem(event, top, player);
            if (restrictions.isBlocked(incoming)) {
                event.setCancelled(true);
                plugin.getLogManager().logBlockedAttempt(
                        data.getOwnerUuid(), data.getOwnerName(), player, data.getCycleId(), incoming,
                        event.getClick().name(), event.getAction().name(), player.getLocation()
                );
                chests.sendBlockedItem(player);
                return;
            }
        }

        if (!affectsTop || !chests.canEdit(player, data)) {
            return;
        }

        ItemStack[] before = ItemStackUtil.cloneArray(top.getContents(), top.getSize());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Inventory currentTop = player.getOpenInventory().getTopInventory();
            if (currentTop.getHolder() != data) {
                return;
            }
            ItemStack[] after = ItemStackUtil.cloneArray(currentTop.getContents(), currentTop.getSize());
            chests.recordInventoryInteraction(player, data, before, after);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EnderChestData data) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < top.getSize());
        if (!touchesTop) {
            return;
        }

        if (!chests.canEdit(player, data)) {
            event.setCancelled(true);
            plugin.getLogManager().logReadOnlyAttempt(
                    data.getOwnerUuid(), data.getOwnerName(), player, data.getCycleId(),
                    "DRAG", "DRAG", player.getLocation()
            );
            return;
        }

        if (!player.hasPermission("enderchest.blocked.bypass") && restrictions.isBlocked(event.getOldCursor())) {
            event.setCancelled(true);
            plugin.getLogManager().logBlockedAttempt(
                    data.getOwnerUuid(), data.getOwnerName(), player, data.getCycleId(), event.getOldCursor(),
                    "DRAG", "DRAG", player.getLocation()
            );
            chests.sendBlockedItem(player);
            return;
        }

        ItemStack[] before = ItemStackUtil.cloneArray(top.getContents(), top.getSize());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Inventory currentTop = player.getOpenInventory().getTopInventory();
            if (currentTop.getHolder() != data) {
                return;
            }
            ItemStack[] after = ItemStackUtil.cloneArray(currentTop.getContents(), currentTop.getSize());
            chests.recordInventoryInteraction(player, data, before, after);
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EnderChestData data) || !(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (data.getViewerCount() <= 1 && chests.canEdit(player, data)) {
            ejectBlockedItemsBeforeSave(player, data, top);
        }
        chests.onViewerClose(player, data);
        untrackPhysicalChest(player.getUniqueId());
    }

    public void shutdown() {
        distanceTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof EnderChestData) {
                player.closeInventory();
            }
        }
        for (BlockKey key : physicalChestViewers.keySet().toArray(BlockKey[]::new)) {
            closePhysicalChest(key);
        }
        physicalChestByViewer.clear();
        physicalChestViewers.clear();
    }

    private void ejectBlockedItemsBeforeSave(Player player, EnderChestData data, Inventory top) {
        if (player.hasPermission("enderchest.blocked.bypass")) {
            return;
        }

        boolean ejectedAny = false;
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (!restrictions.isBlocked(item)) {
                continue;
            }

            ItemStack removed = item.clone();
            top.clear(slot);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(removed);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }

            plugin.getLogManager().logBlockedAttempt(
                    data.getOwnerUuid(), data.getOwnerName(), player, data.getCycleId(), removed,
                    "CLOSE_SECURITY_CHECK", "EJECT_BLOCKED_ITEM", player.getLocation()
            );
            data.markChange(player, 1);
            ejectedAny = true;
        }

        if (ejectedAny) {
            chests.sendBlockedItem(player);
        }
    }

    private boolean affectsTopInventory(InventoryClickEvent event, Inventory top) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            return event.getAction() != InventoryAction.NOTHING;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return clicked != null && clicked != top;
        }
        return event.getAction() == InventoryAction.COLLECT_TO_CURSOR;
    }

    private ItemStack findIncomingItem(InventoryClickEvent event, Inventory top, Player player) {
        Inventory clicked = event.getClickedInventory();
        InventoryAction action = event.getAction();

        if (clicked != top && action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return event.getCurrentItem();
        }
        if (clicked != top) {
            return null;
        }

        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> event.getClick() == ClickType.SWAP_OFFHAND
                    ? player.getInventory().getItemInOffHand()
                    : hotbarItem(player, event.getHotbarButton());
            default -> null;
        };
    }

    private ItemStack hotbarItem(Player player, int hotbarButton) {
        if (hotbarButton < 0 || hotbarButton > 8) {
            return null;
        }
        return player.getInventory().getItem(hotbarButton);
    }

    private void trackPhysicalChest(Player player, Block block) {
        if (!player.isOnline()) {
            return;
        }
        BlockKey key = BlockKey.of(block);
        BlockKey previous = physicalChestByViewer.put(player.getUniqueId(), key);
        if (key.equals(previous)) {
            return;
        }
        if (previous != null) {
            decrementPhysicalChest(previous);
        }
        int viewers = physicalChestViewers.getOrDefault(key, 0);
        physicalChestViewers.put(key, viewers + 1);
        if (viewers == 0 && block.getState() instanceof EnderChest enderChest) {
            enderChest.open();
        }
    }

    private void untrackPhysicalChest(UUID playerUuid) {
        BlockKey key = physicalChestByViewer.remove(playerUuid);
        if (key != null) {
            decrementPhysicalChest(key);
        }
    }

    private void decrementPhysicalChest(BlockKey key) {
        int viewers = physicalChestViewers.getOrDefault(key, 0);
        if (viewers <= 1) {
            physicalChestViewers.remove(key);
            closePhysicalChest(key);
        } else {
            physicalChestViewers.put(key, viewers - 1);
        }
    }

    private void closePhysicalChest(BlockKey key) {
        Block block = key.block();
        if (block != null && block.getState() instanceof EnderChest enderChest) {
            enderChest.close();
        }
    }

    private void checkPhysicalChestDistance() {
        for (Map.Entry<UUID, BlockKey> entry : Map.copyOf(physicalChestByViewer).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                untrackPhysicalChest(entry.getKey());
                continue;
            }
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof EnderChestData)) {
                untrackPhysicalChest(entry.getKey());
                continue;
            }
            Block block = entry.getValue().block();
            if (block == null || block.getType() != Material.ENDER_CHEST) {
                player.closeInventory();
                untrackPhysicalChest(entry.getKey());
                continue;
            }
            Location chestCenter = block.getLocation().add(0.5, 0.5, 0.5);
            if (player.getWorld() != block.getWorld()
                    || player.getLocation().distanceSquared(chestCenter) > MAX_OPEN_DISTANCE_SQUARED) {
                player.closeInventory();
            }
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        private Block block() {
            World world = Bukkit.getWorld(worldId);
            return world == null ? null : world.getBlockAt(x, y, z);
        }
    }
}
