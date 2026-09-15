package com.lopikss.lsenderchest.model;

import com.lopikss.lsenderchest.util.ItemStackUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One live inventory session per chest owner. Every player/admin viewing the same
 * chest shares this exact Bukkit Inventory instance, preventing stale writable copies.
 * All methods are expected to be called on the main server thread.
 */
public final class EnderChestData implements InventoryHolder {

    private final UUID ownerUuid;
    private final String ownerName;
    private final int rows;
    private final Inventory inventory;
    private final Set<UUID> viewers = new LinkedHashSet<>();

    private UUID cycleId = UUID.randomUUID();
    private long cycleStartedAt = System.currentTimeMillis();
    private ItemStack[] cycleBefore = new ItemStack[54];
    private final Set<String> cycleActors = new LinkedHashSet<>();
    private int cycleChangeEvents;
    private long revision;
    private int pendingSaves;
    private boolean retired;

    public EnderChestData(UUID ownerUuid,
                          String ownerName,
                          int rows,
                          ItemStack[] fullContents,
                          Component title) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.rows = Math.max(1, Math.min(6, rows));
        this.inventory = Bukkit.createInventory(this, this.rows * 9, title);

        ItemStack[] normalized = ItemStackUtil.cloneArray(fullContents, 54);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, normalized[i]);
        }
        this.cycleBefore = fullSnapshot();
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int getRows() {
        return rows;
    }

    public UUID getCycleId() {
        return cycleId;
    }

    public long getRevision() {
        return revision;
    }

    public int getViewerCount() {
        return viewers.size();
    }

    public boolean hasViewer(UUID uuid) {
        return viewers.contains(uuid);
    }

    public Set<UUID> getViewerIds() {
        return Set.copyOf(viewers);
    }

    public boolean addViewer(Player player) {
        if (retired) {
            return false;
        }
        if (viewers.isEmpty()) {
            beginCycle();
        }
        viewers.add(player.getUniqueId());
        return true;
    }

    public boolean removeViewer(Player player) {
        return viewers.remove(player.getUniqueId());
    }

    public void markChange(Player actor, int changeCount) {
        if (changeCount <= 0) {
            return;
        }
        revision++;
        cycleChangeEvents += changeCount;
        cycleActors.add(actor.getName() + " (" + actor.getUniqueId() + ")");
    }

    public ItemStack[] fullSnapshot() {
        ItemStack[] full = new ItemStack[54];
        ItemStack[] visible = inventory.getContents();
        for (int i = 0; i < visible.length; i++) {
            full[i] = ItemStackUtil.normalize(visible[i]);
        }
        return full;
    }

    public CycleSnapshot finishCycle() {
        ItemStack[] after = fullSnapshot();
        return new CycleSnapshot(
                cycleId,
                cycleStartedAt,
                System.currentTimeMillis(),
                ItemStackUtil.cloneArray(cycleBefore, 54),
                after,
                List.copyOf(cycleActors),
                cycleChangeEvents,
                revision
        );
    }

    public void beginSave() {
        pendingSaves++;
    }

    public void endSave() {
        if (pendingSaves > 0) {
            pendingSaves--;
        }
    }

    public boolean isSaveInProgress() {
        return pendingSaves > 0;
    }

    public int getPendingSaveCount() {
        return pendingSaves;
    }

    public void retire() {
        retired = true;
    }

    public boolean isRetired() {
        return retired;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void beginCycle() {
        cycleId = UUID.randomUUID();
        cycleStartedAt = System.currentTimeMillis();
        cycleBefore = fullSnapshot();
        cycleActors.clear();
        cycleChangeEvents = 0;
    }

    public record CycleSnapshot(
            UUID sessionId,
            long startedAt,
            long endedAt,
            ItemStack[] before,
            ItemStack[] after,
            List<String> actors,
            int changeEvents,
            long revisionAtClose
    ) {
        public Instant startedInstant() {
            return Instant.ofEpochMilli(startedAt);
        }

        public Instant endedInstant() {
            return Instant.ofEpochMilli(endedAt);
        }
    }
}
