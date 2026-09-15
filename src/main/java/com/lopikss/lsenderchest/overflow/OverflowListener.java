package com.lopikss.lsenderchest.overflow;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class OverflowListener implements Listener {

    private final OverflowManager overflows;

    public OverflowListener(OverflowManager overflows) {
        this.overflows = overflows;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseToken(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!overflows.isOverflowToken(item)) {
            return;
        }

        // Overflow Chests are recovery tokens, never placeable storage containers.
        event.setCancelled(true);
        overflows.redeem(event.getPlayer(), item);
    }
}
