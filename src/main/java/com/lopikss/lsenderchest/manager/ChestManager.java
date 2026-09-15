package com.lopikss.lsenderchest.manager;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.model.EnderChestData;
import com.lopikss.lsenderchest.overflow.OverflowManager;
import com.lopikss.lsenderchest.storage.StorageService;
import com.lopikss.lsenderchest.util.InventoryCodec;
import com.lopikss.lsenderchest.util.ItemStackUtil;
import com.lopikss.lsenderchest.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ChestManager {

    public enum VanillaImportResult {
        CONVERTED,
        ALREADY_COPIED_CLEANED,
        CUSTOM_CHEST_NOT_EMPTY,
        BUSY,
        VANILLA_CHANGED,
        PLAYER_LEFT,
        STORAGE_ERROR
    }

    private final LsEnderChestPlugin plugin;
    private final ConfigManager config;
    private final PlayerIdManager playerIds;
    private final PermissionManager permissions;
    private final StorageService storage;
    private final OverflowManager overflows;
    private final RowStateStore rowState;

    /** Main-thread state. */
    private final Map<UUID, EnderChestData> liveSessions = new HashMap<>();
    private final Map<UUID, List<OpenRequest>> pendingOpenRequests = new HashMap<>();
    private final Set<UUID> loadingOwners = ConcurrentHashMap.newKeySet();
    private final Set<UUID> exclusiveOwners = ConcurrentHashMap.newKeySet();
    private final Set<UUID> convertingOwners = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingResizeRows = new HashMap<>();
    private final Map<UUID, List<CompletableFuture<Void>>> idleWaiters = new HashMap<>();
    private final BukkitTask permissionMonitorTask;

    public ChestManager(LsEnderChestPlugin plugin,
                        ConfigManager config,
                        PlayerIdManager playerIds,
                        PermissionManager permissions,
                        StorageService storage,
                        OverflowManager overflows,
                        RowStateStore rowState) {
        this.plugin = plugin;
        this.config = config;
        this.playerIds = playerIds;
        this.permissions = permissions;
        this.storage = storage;
        this.overflows = overflows;
        this.rowState = rowState;
        this.permissionMonitorTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::checkPermissionRows,
                40L,
                Math.max(20L, config.getPermissionMonitorTicks())
        );
    }

    public void openOwnChest(Player viewer) {
        openOwnChest(viewer, "COMMAND", null);
    }

    public void openOwnChest(Player viewer, String source, Runnable afterOpen) {
        if (!viewer.hasPermission("enderchest.use")) {
            sendNoPermission(viewer);
            return;
        }
        UUID ownerUuid = playerIds.getStorageUuid(viewer);
        int rows = permissions.getRows(viewer);
        openChest(viewer, ownerUuid, viewer.getName(), rows, true, source, afterOpen);
    }

    public void openOtherChest(Player viewer, String targetName) {
        boolean canEdit = viewer.hasPermission("enderchest.admin");
        boolean canView = viewer.hasPermission("enderchest.view.other");
        if (!canEdit && !canView) {
            sendNoPermission(viewer);
            return;
        }

        PlayerIdManager.PlayerIdentity target = playerIds.resolve(targetName).orElse(null);
        if (target == null) {
            sendPlayerNotFound(viewer);
            return;
        }

        Player onlineTarget = Bukkit.getPlayerExact(target.name());
        int rows = onlineTarget != null
                ? permissions.getRows(onlineTarget)
                : rememberedRowsOrUnknown(target.uuid());
        openChest(viewer, target.uuid(), target.name(), rows, false, "COMMAND", null);
    }

    public boolean canEdit(Player viewer, EnderChestData data) {
        if (viewer.hasPermission("enderchest.admin")) {
            return true;
        }
        return viewer.hasPermission("enderchest.use")
                && playerIds.getStorageUuid(viewer).equals(data.getOwnerUuid());
    }

    public void recordInventoryInteraction(Player actor,
                                           EnderChestData data,
                                           ItemStack[] before,
                                           ItemStack[] after) {
        if (!canEdit(actor, data) || ItemStackUtil.contentsEqual(before, after)) {
            return;
        }
        int changes = plugin.getLogManager().logInventoryChanges(
                data.getOwnerUuid(), data.getOwnerName(), data.getCycleId(), actor,
                before, after, actor.getLocation()
        );
        data.markChange(actor, Math.max(1, changes));
        if (actor.hasPermission("enderchest.admin")
                && !playerIds.getStorageUuid(actor).equals(data.getOwnerUuid())) {
            plugin.getLogManager().logAdminAction(
                    data.getOwnerUuid(), data.getOwnerName(), data.getCycleId(), actor,
                    "EDIT", "Changed " + Math.max(1, changes) + " item group(s)"
            );
        }
    }

    public void onViewerClose(Player viewer, EnderChestData data) {
        if (!data.removeViewer(viewer)) {
            return;
        }
        plugin.getLogManager().logClose(
                data.getOwnerUuid(), data.getOwnerName(), data.getCycleId(), viewer,
                data.getViewerCount(), viewer.getLocation()
        );
        if (data.getViewerCount() > 0) {
            return;
        }

        EnderChestData.CycleSnapshot cycle = data.finishCycle();
        persistSession(data, cycle);
    }

    public boolean isConversionInProgress(Player player) {
        return convertingOwners.contains(playerIds.getStorageUuid(player));
    }

    public boolean isOwnerInExclusiveOperation(UUID ownerUuid) {
        return exclusiveOwners.contains(ownerUuid);
    }

    public PlayerIdManager.PlayerIdentity resolveIdentity(String name) {
        return playerIds.resolve(name).orElse(null);
    }

    public CompletableFuture<String> loadSerialized(UUID ownerUuid) {
        return storage.load(ownerUuid);
    }

    public CompletableFuture<Boolean> beginRestoreExclusive(UUID ownerUuid) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runSync(() -> {
            if (!exclusiveOwners.add(ownerUuid)) {
                result.complete(false);
                return;
            }

            EnderChestData session = liveSessions.get(ownerUuid);
            if (session == null) {
                result.complete(true);
                return;
            }

            if (session.getViewerCount() == 0 && !session.isSaveInProgress()) {
                // A previous save failed and the only safe copy may still be in memory.
                // Never hang or overwrite that data with a restore.
                exclusiveOwners.remove(ownerUuid);
                result.complete(false);
                return;
            }

            CompletableFuture<Void> idle = new CompletableFuture<>();
            idleWaiters.computeIfAbsent(ownerUuid, ignored -> new ArrayList<>()).add(idle);
            for (UUID viewerId : session.getViewerIds()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendMessage(TextUtil.component("&eThis Ender Chest is being restored by an administrator."));
                    viewer.closeInventory();
                }
            }
            idle.whenComplete((unused, failure) -> result.complete(failure == null));
        });
        return result;
    }

    public void endRestoreExclusive(UUID ownerUuid) {
        exclusiveOwners.remove(ownerUuid);
    }

    public CompletableFuture<Void> saveRaw(UUID ownerUuid, String ownerName, ItemStack[] contents) {
        final String serialized;
        try {
            serialized = InventoryCodec.serialize(contents);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return storage.save(ownerUuid, ownerName, serialized);
    }

    public void updateRememberedRows(UUID ownerUuid, int rows) {
        runSync(() -> rowState.set(ownerUuid, rows));
    }

    public int getRememberedRows(UUID ownerUuid, ItemStack[] contents) {
        Integer remembered = rowState.get(ownerUuid);
        if (remembered != null) {
            return remembered;
        }
        int highest = ItemStackUtil.highestOccupiedSlot(contents);
        return highest < 0 ? config.getDefaultRows() : Math.max(1, Math.min(6, (highest + 9) / 9));
    }

    public void sendNoPermission(Player player) {
        player.sendMessage(TextUtil.component(config.getMessage("no-permission", "&cYou do not have permission.")));
    }

    public void sendPlayerNotFound(Player player) {
        player.sendMessage(TextUtil.component(config.getMessage("player-not-found", "&cThat player could not be found.")));
    }

    public void sendBlockedItem(Player player) {
        player.sendMessage(TextUtil.component(config.getMessage(
                "blocked-item", "&cThat item is not allowed in your Ender Chest.")));
    }

    public void sendStorageError(Player player) {
        player.sendMessage(TextUtil.component(config.getMessage(
                "storage-error", "&cCould not access that Ender Chest. Check the server console.")));
    }

    public void sendConversionInProgress(Player player) {
        player.sendMessage(TextUtil.component(config.getMessage(
                "conversion-in-progress", "&eYour Ender Chest is being migrated. Try again in a moment.")));
    }

    public void shutdown() {
        permissionMonitorTask.cancel();
        for (EnderChestData session : List.copyOf(liveSessions.values())) {
            for (UUID viewerId : session.getViewerIds()) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null && player.isOnline()) {
                    player.closeInventory();
                }
            }
        }
    }

    /**
     * Imports vanilla Ender Chest data. Conversion is a short exclusive transaction;
     * ordinary owner/admin editing never uses an exclusive lock.
     */
    public CompletableFuture<VanillaImportResult> importVanillaChest(Player player, ItemStack[] vanillaContents) {
        ItemStack[] snapshot = ItemStackUtil.cloneArray(vanillaContents, 27);
        UUID ownerUuid = playerIds.getStorageUuid(player);
        String ownerName = player.getName();
        CompletableFuture<VanillaImportResult> result = new CompletableFuture<>();

        if (!tryBeginExclusiveIfIdle(ownerUuid)) {
            result.complete(VanillaImportResult.BUSY);
            return result;
        }
        convertingOwners.add(ownerUuid);

        storage.load(ownerUuid).whenComplete((serialized, loadFailure) -> {
            if (loadFailure != null) {
                finishConversion(ownerUuid);
                plugin.getLogger().severe("Could not load " + ownerName + "'s custom Ender Chest for conversion: "
                        + rootMessage(loadFailure));
                result.complete(VanillaImportResult.STORAGE_ERROR);
                return;
            }
            runSync(() -> processLoadedVanillaImport(
                    player, snapshot, ownerUuid, ownerName, serialized, result
            ));
        });
        return result;
    }

    private void openChest(Player viewer,
                           UUID ownerUuid,
                           String ownerName,
                           int requestedRows,
                           boolean ownChest,
                           String source,
                           Runnable afterOpen) {
        if (exclusiveOwners.contains(ownerUuid)) {
            viewer.sendMessage(TextUtil.component(config.getMessage(
                    "maintenance-in-progress", "&eThat Ender Chest is temporarily locked for a safe maintenance operation.")));
            return;
        }

        EnderChestData existing = liveSessions.get(ownerUuid);
        if (existing != null && !existing.isRetired()) {
            openExistingSession(viewer, existing, ownChest, source, afterOpen);
            return;
        }

        OpenRequest request = new OpenRequest(viewer.getUniqueId(), ownerUuid, ownerName, requestedRows, ownChest, source, afterOpen);
        pendingOpenRequests.computeIfAbsent(ownerUuid, ignored -> new ArrayList<>()).add(request);
        if (!loadingOwners.add(ownerUuid)) {
            return;
        }

        storage.load(ownerUuid).whenComplete((serialized, failure) -> runSync(() -> {
            if (failure != null) {
                plugin.getLogger().severe("Could not load " + ownerName + "'s Ender Chest: " + rootMessage(failure));
                failPendingOpens(ownerUuid);
                return;
            }

            ItemStack[] loaded;
            try {
                loaded = InventoryCodec.deserialize(serialized);
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Could not decode " + ownerName + "'s Ender Chest: " + exception.getMessage());
                failPendingOpens(ownerUuid);
                return;
            }

            List<OpenRequest> requests = pendingOpenRequests.getOrDefault(ownerUuid, List.of());
            int effectiveRows = determineEffectiveRows(ownerUuid, requests, loaded);
            Integer previousRows = rowState.get(ownerUuid);

            prepareCapacity(ownerUuid, ownerName, loaded, effectiveRows, previousRows)
                    .whenComplete((prepared, prepareFailure) -> runSync(() -> {
                        if (prepareFailure != null) {
                            plugin.getLogger().severe("Could not apply safe row capacity for " + ownerName + ": "
                                    + rootMessage(prepareFailure));
                            failPendingOpens(ownerUuid);
                            return;
                        }

                        if (!prepared.changed()) {
                            finishOpening(ownerUuid, ownerName, effectiveRows, prepared.contents());
                            return;
                        }

                        saveRaw(ownerUuid, ownerName, prepared.contents()).whenComplete((unused, saveFailure) -> runSync(() -> {
                            if (saveFailure != null) {
                                if (prepared.overflow() != null) {
                                    overflows.retire(List.of(prepared.overflow().record().id()));
                                }
                                plugin.getLogger().severe("Could not save overflow-protected resize for " + ownerName + ": "
                                        + rootMessage(saveFailure));
                                failPendingOpens(ownerUuid);
                                return;
                            }
                            rowState.set(ownerUuid, effectiveRows);
                            recordResizeSnapshots(ownerUuid, ownerName, prepared).whenComplete((snapshotIgnored, snapshotFailure) -> {
                                if (snapshotFailure == null && prepared.overflow() != null) {
                                    overflows.retire(prepared.overflow().retiredOverflowIds());
                                }
                            });
                            finishOpening(ownerUuid, ownerName, effectiveRows, prepared.contents());
                        }));
                    }));
        }));
    }

    private void finishOpening(UUID ownerUuid, String ownerName, int rows, ItemStack[] contents) {
        if (exclusiveOwners.contains(ownerUuid)) {
            failPendingOpens(ownerUuid);
            return;
        }
        String title = config.getChestTitle(ownerName);
        EnderChestData session = new EnderChestData(
                ownerUuid, ownerName, rows, contents,
                TextUtil.component(title)
        );
        liveSessions.put(ownerUuid, session);
        rowState.set(ownerUuid, rows);

        List<OpenRequest> requests = pendingOpenRequests.remove(ownerUuid);
        loadingOwners.remove(ownerUuid);
        if (requests == null) {
            completeIdleWaitersIfIdle(ownerUuid);
            return;
        }
        for (OpenRequest request : requests) {
            Player viewer = Bukkit.getPlayer(request.viewerUuid());
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            openExistingSession(viewer, session, request.ownChest(), request.source(), request.afterOpen());
        }
    }

    private void openExistingSession(Player viewer,
                                     EnderChestData session,
                                     boolean ownChest,
                                     String source,
                                     Runnable afterOpen) {
        if (session.hasViewer(viewer.getUniqueId())
                && viewer.getOpenInventory().getTopInventory().getHolder() == session) {
            if (afterOpen != null) {
                afterOpen.run();
            }
            return;
        }

        if (!session.addViewer(viewer)) {
            viewer.sendMessage(TextUtil.component("&eThat Ender Chest is finishing a save. Try again."));
            return;
        }

        viewer.openInventory(session.getInventory());
        boolean adminEdit = viewer.hasPermission("enderchest.admin")
                && !playerIds.getStorageUuid(viewer).equals(session.getOwnerUuid());
        String mode = ownChest ? "OWN" : (canEdit(viewer, session) ? "ADMIN_EDIT" : "READ_ONLY");
        plugin.getLogManager().logOpen(
                session.getOwnerUuid(), session.getOwnerName(), session.getCycleId(),
                viewer, mode, source, viewer.getLocation()
        );
        if (adminEdit) {
            plugin.getLogManager().logAdminAction(
                    session.getOwnerUuid(), session.getOwnerName(), session.getCycleId(),
                    viewer, "OPEN", "Opened another player's Ender Chest"
            );
        }

        if (!ownChest) {
            viewer.sendMessage(TextUtil.component(
                    config.getMessage("opened-other", "&aOpened %player%'s Ender Chest.")
                            .replace("%player%", session.getOwnerName())));
        }
        if (afterOpen != null) {
            afterOpen.run();
        }
    }

    private void persistSession(EnderChestData data, EnderChestData.CycleSnapshot cycle) {
        final String serialized;
        try {
            serialized = InventoryCodec.serialize(cycle.after());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not serialize " + data.getOwnerName() + "'s Ender Chest: " + exception.getMessage());
            return;
        }

        data.beginSave();
        storage.save(data.getOwnerUuid(), data.getOwnerName(), serialized).whenComplete((unused, failure) -> runSync(() -> {
            if (failure != null) {
                data.endSave();
                plugin.getLogger().severe("Could not save " + data.getOwnerName() + "'s Ender Chest: " + rootMessage(failure));
                for (UUID viewerId : data.getViewerIds()) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null && viewer.isOnline()) {
                        sendStorageError(viewer);
                    }
                }
                return;
            }

            if (!ItemStackUtil.contentsEqual(cycle.before(), cycle.after())) {
                plugin.getRestoreManager().recordSessionPoint(data, cycle);
            }

            data.endSave();
            Integer pendingRows = pendingResizeRows.get(data.getOwnerUuid());
            if (pendingRows != null && data.getViewerCount() == 0 && !data.isSaveInProgress()) {
                pendingResizeRows.remove(data.getOwnerUuid());
                data.retire();
                liveSessions.remove(data.getOwnerUuid(), data);
                resizeStoredChest(data.getOwnerUuid(), data.getOwnerName(), pendingRows, data.getRows());
                completeIdleWaitersIfIdle(data.getOwnerUuid());
                return;
            }

            if (data.getViewerCount() == 0
                    && !data.isSaveInProgress()
                    && data.getRevision() == cycle.revisionAtClose()) {
                data.retire();
                liveSessions.remove(data.getOwnerUuid(), data);
                completeIdleWaitersIfIdle(data.getOwnerUuid());
            }
        }));
    }

    private CompletableFuture<PreparedContents> prepareCapacity(UUID ownerUuid,
                                                                 String ownerName,
                                                                 ItemStack[] fullContents,
                                                                 int targetRows,
                                                                 Integer previousRows) {
        ItemStack[] original = ItemStackUtil.cloneArray(fullContents, 54);
        ItemStack[] transformed = ItemStackUtil.cloneArray(fullContents, 54);
        int highest = ItemStackUtil.highestOccupiedSlot(transformed);
        boolean knownDowngrade = previousRows != null && previousRows > targetRows;
        boolean hiddenBeyondCapacity = highest >= targetRows * 9;

        if (!knownDowngrade && !hiddenBeyondCapacity) {
            rowState.set(ownerUuid, targetRows);
            return CompletableFuture.completedFuture(new PreparedContents(
                    transformed, false, original, targetRows, targetRows, null
            ));
        }

        int sourceRows = previousRows != null && previousRows > targetRows
                ? previousRows
                : Math.max(targetRows + 1, Math.min(6, (highest + 9) / 9));
        int overflowSlot = targetRows * 9 - 1;
        List<ItemStack> displaced = ItemStackUtil.cloneNonEmpty(transformed, overflowSlot, transformed.length);
        for (int i = overflowSlot; i < transformed.length; i++) {
            transformed[i] = null;
        }

        if (displaced.isEmpty()) {
            return CompletableFuture.completedFuture(new PreparedContents(
                    transformed, true, original, sourceRows, targetRows, null
            ));
        }

        CompletableFuture<PreparedContents> result = new CompletableFuture<>();
        overflows.createOverflow(ownerUuid, displaced, sourceRows, targetRows)
                .whenComplete((overflow, failure) -> runSync(() -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    transformed[overflowSlot] = overflow.token();
                    result.complete(new PreparedContents(
                            transformed, true, original, sourceRows, targetRows, overflow
                    ));
                }));
        return result;
    }

    private void resizeStoredChest(UUID ownerUuid, String ownerName, int targetRows, int previousRows) {
        if (!exclusiveOwners.add(ownerUuid)) {
            pendingResizeRows.merge(ownerUuid, targetRows, Math::min);
            return;
        }

        storage.load(ownerUuid).whenComplete((serialized, loadFailure) -> runSync(() -> {
            if (loadFailure != null) {
                exclusiveOwners.remove(ownerUuid);
                plugin.getLogger().severe("Could not load " + ownerName + " for row downgrade: " + rootMessage(loadFailure));
                return;
            }
            ItemStack[] full;
            try {
                full = InventoryCodec.deserialize(serialized);
            } catch (RuntimeException exception) {
                exclusiveOwners.remove(ownerUuid);
                plugin.getLogger().severe("Could not decode " + ownerName + " for row downgrade: " + exception.getMessage());
                return;
            }

            prepareCapacity(ownerUuid, ownerName, full, targetRows, previousRows)
                    .whenComplete((prepared, prepareFailure) -> runSync(() -> {
                        if (prepareFailure != null) {
                            exclusiveOwners.remove(ownerUuid);
                            plugin.getLogger().severe("Could not create Overflow Chest for " + ownerName + ": "
                                    + rootMessage(prepareFailure));
                            return;
                        }
                        if (!prepared.changed()) {
                            rowState.set(ownerUuid, targetRows);
                            exclusiveOwners.remove(ownerUuid);
                            return;
                        }

                        saveRaw(ownerUuid, ownerName, prepared.contents()).whenComplete((unused, saveFailure) -> runSync(() -> {
                            exclusiveOwners.remove(ownerUuid);
                            if (saveFailure != null) {
                                if (prepared.overflow() != null) {
                                    overflows.retire(List.of(prepared.overflow().record().id()));
                                }
                                plugin.getLogger().severe("Could not save row downgrade for " + ownerName + ": "
                                        + rootMessage(saveFailure));
                                return;
                            }
                            rowState.set(ownerUuid, targetRows);
                            recordResizeSnapshots(ownerUuid, ownerName, prepared).whenComplete((snapshotIgnored, snapshotFailure) -> {
                                if (snapshotFailure == null && prepared.overflow() != null) {
                                    overflows.retire(prepared.overflow().retiredOverflowIds());
                                }
                            });

                            Player online = Bukkit.getPlayerExact(ownerName);
                            if (online != null && online.isOnline()) {
                                online.sendMessage(TextUtil.component(config.getMessage(
                                        "overflow-created",
                                        "&eYour Ender Chest got smaller. Extra items were safely moved into an &6Overflow Chest&e in the final slot."
                                )));
                            }
                        }));
                    }));
        }));
    }

    private CompletableFuture<Void> recordResizeSnapshots(UUID ownerUuid, String ownerName, PreparedContents prepared) {
        String summary = "Rows changed " + prepared.sourceRows() + " -> " + prepared.targetRows();
        CompletableFuture<Void> before = plugin.getRestoreManager().recordSystemPoint(
                ownerUuid, ownerName, "BEFORE_ROW_DOWNGRADE", prepared.sourceRows(),
                prepared.before(), List.of("System"), "Before " + summary
        );
        CompletableFuture<Void> after = plugin.getRestoreManager().recordSystemPoint(
                ownerUuid, ownerName, "ROW_DOWNGRADE", prepared.targetRows(),
                prepared.contents(), List.of("System"), summary
        );
        plugin.getLogManager().logSystem(ownerUuid, UUID.randomUUID(), "ROW_DOWNGRADE", summary);
        return CompletableFuture.allOf(before, after);
    }

    private void checkPermissionRows() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID ownerUuid = playerIds.getStorageUuid(player);
            int currentRows = permissions.getRows(player);
            Integer previousRows = rowState.get(ownerUuid);
            if (previousRows == null) {
                rowState.set(ownerUuid, currentRows);
                continue;
            }
            if (currentRows > previousRows) {
                rowState.set(ownerUuid, currentRows);
                continue;
            }
            if (currentRows >= previousRows) {
                continue;
            }

            EnderChestData session = liveSessions.get(ownerUuid);
            if (session != null) {
                // Defer every resize while a live in-memory session exists. This includes a
                // failed-save session: the in-memory copy may be newer than the database.
                pendingResizeRows.merge(ownerUuid, currentRows, Math::min);
                continue;
            }
            resizeStoredChest(ownerUuid, player.getName(), currentRows, previousRows);
        }
    }

    private int determineEffectiveRows(UUID ownerUuid, List<OpenRequest> requests, ItemStack[] loaded) {
        int knownMin = 7;
        for (OpenRequest request : requests) {
            if (request.requestedRows() > 0) {
                knownMin = Math.min(knownMin, request.requestedRows());
            }
        }
        if (knownMin <= 6) {
            return knownMin;
        }

        Integer remembered = rowState.get(ownerUuid);
        if (remembered != null) {
            return remembered;
        }
        int highest = ItemStackUtil.highestOccupiedSlot(loaded);
        int inferred = highest < 0 ? config.getDefaultRows() : Math.max(1, Math.min(6, (highest + 9) / 9));
        return Math.max(config.getDefaultRows(), inferred);
    }

    private int rememberedRowsOrUnknown(UUID ownerUuid) {
        Integer remembered = rowState.get(ownerUuid);
        return remembered == null ? 0 : remembered;
    }

    private void failPendingOpens(UUID ownerUuid) {
        List<OpenRequest> requests = pendingOpenRequests.remove(ownerUuid);
        loadingOwners.remove(ownerUuid);
        if (requests != null) {
            for (OpenRequest request : requests) {
                Player viewer = Bukkit.getPlayer(request.viewerUuid());
                if (viewer != null && viewer.isOnline()) {
                    sendStorageError(viewer);
                }
            }
        }
        completeIdleWaitersIfIdle(ownerUuid);
    }

    private boolean tryBeginExclusiveIfIdle(UUID ownerUuid) {
        if (exclusiveOwners.contains(ownerUuid)
                || loadingOwners.contains(ownerUuid)
                || liveSessions.containsKey(ownerUuid)) {
            return false;
        }
        return exclusiveOwners.add(ownerUuid);
    }

    private void finishConversion(UUID ownerUuid) {
        convertingOwners.remove(ownerUuid);
        exclusiveOwners.remove(ownerUuid);
        completeIdleWaitersIfIdle(ownerUuid);
    }

    private void processLoadedVanillaImport(Player player,
                                            ItemStack[] snapshot,
                                            UUID ownerUuid,
                                            String ownerName,
                                            String serialized,
                                            CompletableFuture<VanillaImportResult> result) {
        if (!player.isOnline()) {
            finishConversion(ownerUuid);
            result.complete(VanillaImportResult.PLAYER_LEFT);
            return;
        }

        final ItemStack[] existing;
        try {
            existing = InventoryCodec.deserialize(serialized);
        } catch (RuntimeException exception) {
            finishConversion(ownerUuid);
            plugin.getLogger().severe("Could not decode " + ownerName + "'s custom Ender Chest during conversion: "
                    + exception.getMessage());
            result.complete(VanillaImportResult.STORAGE_ERROR);
            return;
        }

        if (!ItemStackUtil.isEmpty(existing)) {
            if (ItemStackUtil.contentsEqual(existing, snapshot, 27)) {
                completeVanillaCleanup(
                        player, snapshot, ownerUuid,
                        VanillaImportResult.ALREADY_COPIED_CLEANED, result
                );
            } else {
                finishConversion(ownerUuid);
                result.complete(VanillaImportResult.CUSTOM_CHEST_NOT_EMPTY);
            }
            return;
        }

        ItemStack[] converted = new ItemStack[54];
        for (int i = 0; i < snapshot.length; i++) {
            converted[i] = ItemStackUtil.normalize(snapshot[i]);
        }

        final String convertedSerialized;
        final String previousSerialized;
        try {
            convertedSerialized = InventoryCodec.serialize(converted);
            previousSerialized = serialized == null || serialized.isBlank()
                    ? InventoryCodec.serialize(existing)
                    : serialized;
        } catch (RuntimeException exception) {
            finishConversion(ownerUuid);
            plugin.getLogger().severe("Could not serialize " + ownerName + "'s conversion data: " + exception.getMessage());
            result.complete(VanillaImportResult.STORAGE_ERROR);
            return;
        }

        storage.save(ownerUuid, ownerName, convertedSerialized).whenComplete((unused, saveFailure) -> {
            if (saveFailure != null) {
                finishConversion(ownerUuid);
                plugin.getLogger().severe("Could not save converted Ender Chest for " + ownerName + ": "
                        + rootMessage(saveFailure));
                result.complete(VanillaImportResult.STORAGE_ERROR);
                return;
            }
            verifyAndClearAfterWrite(
                    player, snapshot, ownerUuid, ownerName,
                    previousSerialized, converted, result
            );
        });
    }

    private void verifyAndClearAfterWrite(Player player,
                                          ItemStack[] snapshot,
                                          UUID ownerUuid,
                                          String ownerName,
                                          String previousSerialized,
                                          ItemStack[] converted,
                                          CompletableFuture<VanillaImportResult> result) {
        runSync(() -> {
            if (!player.isOnline()) {
                rollbackConversion(ownerUuid, ownerName, previousSerialized, VanillaImportResult.PLAYER_LEFT, result);
                return;
            }
            if (!ItemStackUtil.contentsEqual(player.getEnderChest().getContents(), snapshot, 27)) {
                rollbackConversion(ownerUuid, ownerName, previousSerialized, VanillaImportResult.VANILLA_CHANGED, result);
                return;
            }

            player.getEnderChest().clear();
            plugin.getRestoreManager().recordSystemPoint(
                    ownerUuid, ownerName, "VANILLA_CONVERSION", 3,
                    converted, List.of("System"), "Imported vanilla Ender Chest contents"
            );
            plugin.getLogManager().logSystem(
                    ownerUuid, UUID.randomUUID(), "VANILLA_CONVERSION",
                    "Imported vanilla Ender Chest contents and cleared verified vanilla copy"
            );
            finishConversion(ownerUuid);
            result.complete(VanillaImportResult.CONVERTED);
        });
    }

    private void completeVanillaCleanup(Player player,
                                        ItemStack[] snapshot,
                                        UUID ownerUuid,
                                        VanillaImportResult successResult,
                                        CompletableFuture<VanillaImportResult> result) {
        runSync(() -> {
            if (!player.isOnline()) {
                finishConversion(ownerUuid);
                result.complete(VanillaImportResult.PLAYER_LEFT);
                return;
            }
            if (!ItemStackUtil.contentsEqual(player.getEnderChest().getContents(), snapshot, 27)) {
                finishConversion(ownerUuid);
                result.complete(VanillaImportResult.VANILLA_CHANGED);
                return;
            }
            player.getEnderChest().clear();
            plugin.getLogManager().logSystem(
                    ownerUuid, UUID.randomUUID(), "VANILLA_CONVERSION_CLEANUP",
                    "Verified matching old-converter copy and cleared duplicate vanilla contents"
            );
            finishConversion(ownerUuid);
            result.complete(successResult);
        });
    }

    private void rollbackConversion(UUID ownerUuid,
                                    String ownerName,
                                    String previousSerialized,
                                    VanillaImportResult resultValue,
                                    CompletableFuture<VanillaImportResult> result) {
        storage.save(ownerUuid, ownerName, previousSerialized).whenComplete((unused, rollbackFailure) -> {
            finishConversion(ownerUuid);
            if (rollbackFailure != null) {
                plugin.getLogger().severe("CRITICAL: could not roll back a cancelled Ender Chest conversion for "
                        + ownerName + ": " + rootMessage(rollbackFailure));
                result.complete(VanillaImportResult.STORAGE_ERROR);
            } else {
                result.complete(resultValue);
            }
        });
    }

    private void completeIdleWaitersIfIdle(UUID ownerUuid) {
        if (liveSessions.containsKey(ownerUuid) || loadingOwners.contains(ownerUuid)) {
            return;
        }
        List<CompletableFuture<Void>> waiters = idleWaiters.remove(ownerUuid);
        if (waiters != null) {
            for (CompletableFuture<Void> waiter : waiters) {
                waiter.complete(null);
            }
        }
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

    private record OpenRequest(
            UUID viewerUuid,
            UUID ownerUuid,
            String ownerName,
            int requestedRows,
            boolean ownChest,
            String source,
            Runnable afterOpen
    ) {
    }

    private record PreparedContents(
            ItemStack[] contents,
            boolean changed,
            ItemStack[] before,
            int sourceRows,
            int targetRows,
            OverflowManager.CreatedOverflow overflow
    ) {
    }
}
