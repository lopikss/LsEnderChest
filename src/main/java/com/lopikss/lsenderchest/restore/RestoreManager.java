package com.lopikss.lsenderchest.restore;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.log.LogManager;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import com.lopikss.lsenderchest.model.EnderChestData;
import com.lopikss.lsenderchest.overflow.OverflowManager;
import com.lopikss.lsenderchest.overflow.OverflowRecord;
import com.lopikss.lsenderchest.util.InventoryCodec;
import com.lopikss.lsenderchest.util.ItemStackUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RestoreManager {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final DateTimeFormatter GUI_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final LsEnderChestPlugin plugin;
    private final ChestManager chests;
    private final OverflowManager overflows;
    private final LogManager logs;

    public RestoreManager(LsEnderChestPlugin plugin,
                          ChestManager chests,
                          OverflowManager overflows,
                          LogManager logs) {
        this.plugin = plugin;
        this.chests = chests;
        this.overflows = overflows;
        this.logs = logs;
    }

    public void recordSessionPoint(EnderChestData data, EnderChestData.CycleSnapshot cycle) {
        String summary = cycle.changeEvents() + " logged change group(s), "
                + Math.max(0L, (cycle.endedAt() - cycle.startedAt()) / 1000L) + "s session";
        captureSnapshot(cycle.after()).thenCompose(snapshot -> logs.recordRestorePoint(
                data.getOwnerUuid(), data.getOwnerName(), cycle.sessionId(),
                "SESSION", data.getRows(), cycle.actors(), summary, snapshot
        )).exceptionally(failure -> {
            plugin.getLogger().warning("Could not create restore point for " + data.getOwnerName() + ": " + rootMessage(failure));
            return null;
        });
    }

    public CompletableFuture<Void> recordSystemPoint(UUID ownerUuid,
                                  String ownerName,
                                  String kind,
                                  int rows,
                                  ItemStack[] contents,
                                  List<String> actors,
                                  String summary) {
        UUID sessionId = UUID.randomUUID();
        return captureSnapshot(contents).thenCompose(snapshot -> logs.recordRestorePoint(
                ownerUuid, ownerName, sessionId, kind, rows, actors, summary, snapshot
        )).whenComplete((unused, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("Could not create system restore point for " + ownerName + ": " + rootMessage(failure));
            }
        });
    }

    public void openHistory(Player admin, PlayerIdManager.PlayerIdentity target) {
        logs.loadRestorePoints(target.uuid()).whenComplete((points, failure) -> runSync(() -> {
            if (!admin.isOnline()) {
                return;
            }
            if (failure != null) {
                admin.sendMessage(Component.text("Could not read restore history. Check the console.", NamedTextColor.RED));
                plugin.getLogger().severe("Could not read restore history for " + target.name() + ": " + rootMessage(failure));
                return;
            }
            if (points.isEmpty()) {
                admin.sendMessage(Component.text("No restore points exist for " + target.name() + " yet.", NamedTextColor.YELLOW));
                return;
            }
            openHistoryPage(admin, target, points, 0);
        }));
    }

    void openHistoryPage(Player admin,
                         PlayerIdManager.PlayerIdentity target,
                         List<LogManager.RestorePoint> points,
                         int requestedPage) {
        int pages = Math.max(1, (points.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        RestoreHistoryHolder holder = new RestoreHistoryHolder(target, points, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Restore • " + target.name(), NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        int start = page * 45;
        for (int slot = 0; slot < 45; slot++) {
            int index = start + slot;
            if (index >= points.size()) {
                break;
            }
            inventory.setItem(slot, restorePointItem(points.get(index)));
        }
        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "Previous page", NamedTextColor.AQUA));
        }
        inventory.setItem(49, button(Material.BOOK, "Page " + (page + 1) + "/" + pages, NamedTextColor.GRAY));
        if (page + 1 < pages) {
            inventory.setItem(53, button(Material.ARROW, "Next page", NamedTextColor.AQUA));
        }
        admin.openInventory(inventory);
    }

    void openPreview(Player admin,
                     PlayerIdManager.PlayerIdentity target,
                     LogManager.RestorePoint point,
                     int historyPage,
                     int contentPage) {
        SnapshotBundle bundle;
        try {
            bundle = decodeSnapshot(point.snapshotData());
        } catch (RuntimeException exception) {
            admin.sendMessage(Component.text("That restore point is damaged and cannot be previewed.", NamedTextColor.RED));
            return;
        }

        ItemStack[] contents = bundle.chestContents();
        int visibleSlots = Math.max(9, Math.min(54, point.rows() * 9));
        int pages = Math.max(1, (visibleSlots + 44) / 45);
        int page = Math.max(0, Math.min(contentPage, pages - 1));
        RestorePreviewHolder holder = new RestorePreviewHolder(target, point, historyPage, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Preview • " + GUI_TIME.format(point.instant()), NamedTextColor.GOLD));
        holder.inventory = inventory;

        int start = page * 45;
        for (int slot = 0; slot < 45; slot++) {
            int index = start + slot;
            if (index >= visibleSlots || index >= contents.length) {
                break;
            }
            inventory.setItem(slot, ItemStackUtil.normalize(contents[index]));
        }
        inventory.setItem(45, button(Material.ARROW, page > 0 ? "Previous contents" : "Back to history", NamedTextColor.AQUA));
        inventory.setItem(49, button(Material.LIME_CONCRETE, "Restore this snapshot", NamedTextColor.GREEN));
        if (page + 1 < pages) {
            inventory.setItem(53, button(Material.ARROW, "Next contents", NamedTextColor.AQUA));
        }
        admin.openInventory(inventory);
    }

    void openConfirm(Player admin,
                     PlayerIdManager.PlayerIdentity target,
                     LogManager.RestorePoint point,
                     int historyPage) {
        RestoreConfirmHolder holder = new RestoreConfirmHolder(target, point, historyPage);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Confirm restore • " + target.name(), NamedTextColor.RED));
        holder.inventory = inventory;

        ItemStack info = button(Material.CLOCK, GUI_TIME.format(point.instant()), NamedTextColor.YELLOW);
        info.editMeta(meta -> meta.lore(List.of(
                Component.text(point.kind(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(point.summary(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        )));
        inventory.setItem(13, info);
        inventory.setItem(11, button(Material.LIME_CONCRETE, "CONFIRM RESTORE", NamedTextColor.GREEN));
        inventory.setItem(15, button(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        admin.openInventory(inventory);
    }

    public void restore(Player admin,
                        PlayerIdManager.PlayerIdentity target,
                        LogManager.RestorePoint point) {
        admin.closeInventory();
        admin.sendMessage(Component.text("Preparing safe restore for " + target.name() + "...", NamedTextColor.YELLOW));

        chests.beginRestoreExclusive(target.uuid()).whenComplete((locked, lockFailure) -> {
            if (lockFailure != null || !Boolean.TRUE.equals(locked)) {
                runSync(() -> admin.sendMessage(Component.text(
                        "That Ender Chest is already in another maintenance operation.", NamedTextColor.RED)));
                return;
            }

            chests.loadSerialized(target.uuid()).whenComplete((currentSerialized, loadFailure) -> runSync(() -> {
                if (loadFailure != null) {
                    chests.endRestoreExclusive(target.uuid());
                    admin.sendMessage(Component.text("Could not load the current chest before restore.", NamedTextColor.RED));
                    return;
                }

                ItemStack[] current;
                SnapshotBundle historical;
                try {
                    current = InventoryCodec.deserialize(currentSerialized);
                    historical = decodeSnapshot(point.snapshotData());
                } catch (RuntimeException exception) {
                    chests.endRestoreExclusive(target.uuid());
                    admin.sendMessage(Component.text("That restore point is damaged and cannot be restored.", NamedTextColor.RED));
                    return;
                }

                // Create a reversible checkpoint before touching the live data. The restore does not
                // continue until the snapshot (including referenced Overflow Chests) has been captured.
                int currentRows = chests.getRememberedRows(target.uuid(), current);
                recordSystemPoint(
                        target.uuid(), target.name(), "PRE_RESTORE_BACKUP", currentRows,
                        current, List.of(admin.getName() + " (" + admin.getUniqueId() + ")"),
                        "Automatic backup before restoring " + GUI_TIME.format(point.instant())
                ).whenComplete((backupIgnored, backupFailure) -> runSync(() -> {
                    if (backupFailure != null) {
                        chests.endRestoreExclusive(target.uuid());
                        admin.sendMessage(Component.text("Could not create the required pre-restore backup.", NamedTextColor.RED));
                        return;
                    }

                    overflows.cloneEmbeddedOverflows(target.uuid(), historical.chestContents(), historical.overflows())
                            .whenComplete((cloned, cloneFailure) -> runSync(() -> {
                                if (cloneFailure != null) {
                                    chests.endRestoreExclusive(target.uuid());
                                    admin.sendMessage(Component.text("Could not recreate historical Overflow Chests.", NamedTextColor.RED));
                                    return;
                                }

                                chests.saveRaw(target.uuid(), target.name(), cloned.chestContents())
                                        .whenComplete((unused, saveFailure) -> runSync(() -> {
                                            chests.endRestoreExclusive(target.uuid());
                                            if (saveFailure != null) {
                                                overflows.retire(cloned.createdOverflowIds());
                                                admin.sendMessage(Component.text("Restore failed while saving. Current data was left unchanged.", NamedTextColor.RED));
                                                plugin.getLogger().severe("Restore save failed for " + target.name() + ": " + rootMessage(saveFailure));
                                                return;
                                            }

                                            chests.updateRememberedRows(target.uuid(), point.rows());
                                            plugin.getLogManager().logAdminAction(
                                                    target.uuid(), target.name(), UUID.randomUUID(), admin,
                                                    "RESTORE", "Restored snapshot from " + GUI_TIME.format(point.instant())
                                            );
                                            recordSystemPoint(
                                                    target.uuid(), target.name(), "RESTORE", point.rows(),
                                                    cloned.chestContents(),
                                                    List.of(admin.getName() + " (" + admin.getUniqueId() + ")"),
                                                    "Restored snapshot from " + GUI_TIME.format(point.instant())
                                            );
                                            admin.sendMessage(Component.text(
                                                    "Restored " + target.name() + " to " + GUI_TIME.format(point.instant()) + ".",
                                                    NamedTextColor.GREEN));
                                        }));
                            }));
                }));
            }));
        });
    }

    private CompletableFuture<String> captureSnapshot(ItemStack[] contents) {
        ItemStack[] chest = ItemStackUtil.cloneArray(contents, 54);
        final String chestSerialized;
        try {
            chestSerialized = InventoryCodec.serialize(chest);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return overflows.captureReferences(chest).thenApply(records -> encodeSnapshot(chestSerialized, records));
    }

    private String encodeSnapshot(String chestSerialized, List<OverflowRecord> records) {
        StringBuilder builder = new StringBuilder("LSREC1\n");
        builder.append("CHEST=").append(enc(chestSerialized)).append('\n');
        for (OverflowRecord record : records) {
            builder.append("OVERFLOW=")
                    .append(record.id()).append('|')
                    .append(record.ownerUuid()).append('|')
                    .append(record.createdAt()).append('|')
                    .append(record.sourceRows()).append('|')
                    .append(record.targetRows()).append('|')
                    .append(enc(record.contents()))
                    .append('\n');
        }
        return builder.toString();
    }

    private SnapshotBundle decodeSnapshot(String data) {
        if (data == null || !data.startsWith("LSREC1\n")) {
            throw new IllegalArgumentException("Unsupported restore snapshot format.");
        }
        String chestSerialized = null;
        List<OverflowRecord> embedded = new ArrayList<>();
        for (String line : data.lines().toList()) {
            if (line.startsWith("CHEST=")) {
                chestSerialized = dec(line.substring("CHEST=".length()));
            } else if (line.startsWith("OVERFLOW=")) {
                String[] fields = line.substring("OVERFLOW=".length()).split("\\|", 6);
                if (fields.length == 6) {
                    embedded.add(new OverflowRecord(
                            UUID.fromString(fields[0]),
                            UUID.fromString(fields[1]),
                            Long.parseLong(fields[2]),
                            Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4]),
                            dec(fields[5])
                    ));
                }
            }
        }
        if (chestSerialized == null) {
            throw new IllegalArgumentException("Restore snapshot has no chest payload.");
        }
        return new SnapshotBundle(InventoryCodec.deserialize(chestSerialized), List.copyOf(embedded));
    }

    private ItemStack restorePointItem(LogManager.RestorePoint point) {
        ItemStack item = ItemStack.of(Material.CLOCK, 1);
        item.editMeta(meta -> {
            meta.displayName(Component.text(GUI_TIME.format(point.instant()), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(point.kind().replace('_', ' '), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(point.summary(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Rows: " + point.rows(), NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            if (!point.actors().isEmpty()) {
                lore.add(Component.text("Actors: " + String.join(", ", point.actors()), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Left-click: preview", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Right-click: restore", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        return item;
    }

    private ItemStack button(Material material, String name, NamedTextColor color) {
        ItemStack item = ItemStack.of(material, 1);
        item.editMeta(meta -> meta.displayName(Component.text(name, color)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private String enc(String value) {
        return B64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String dec(String value) {
        return new String(B64D.decode(value), StandardCharsets.UTF_8);
    }

    private void runSync(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record SnapshotBundle(ItemStack[] chestContents, List<OverflowRecord> overflows) {
    }
}
