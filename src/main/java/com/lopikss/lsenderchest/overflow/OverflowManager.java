package com.lopikss.lsenderchest.overflow;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import com.lopikss.lsenderchest.storage.StorageService;
import com.lopikss.lsenderchest.util.InventoryCodec;
import com.lopikss.lsenderchest.util.ItemStackUtil;
import com.lopikss.lsenderchest.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OverflowManager {

    private static final String TOKEN_VERSION = "1";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final LsEnderChestPlugin plugin;
    private final StorageService storage;
    private final PlayerIdManager playerIds;
    private final NamespacedKey idKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey signatureKey;
    private final byte[] secret;
    private final Set<UUID> redeeming = new HashSet<>();

    public OverflowManager(LsEnderChestPlugin plugin, StorageService storage, PlayerIdManager playerIds) {
        this.plugin = plugin;
        this.storage = storage;
        this.playerIds = playerIds;
        this.idKey = new NamespacedKey(plugin, "overflow_id");
        this.ownerKey = new NamespacedKey(plugin, "overflow_owner");
        this.versionKey = new NamespacedKey(plugin, "overflow_version");
        this.signatureKey = new NamespacedKey(plugin, "overflow_signature");
        this.secret = loadOrCreateSecret();
    }

    public CompletableFuture<CreatedOverflow> createOverflow(UUID ownerUuid,
                                                              List<ItemStack> sourceItems,
                                                              int sourceRows,
                                                              int targetRows) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<CreatedOverflow> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> createOverflow(ownerUuid, sourceItems, sourceRows, targetRows)
                    .whenComplete((value, failure) -> completeForward(future, value, failure)));
            return future;
        }

        List<SourceEntry> entries = new ArrayList<>();
        Set<UUID> referencedIds = new LinkedHashSet<>();

        for (ItemStack source : sourceItems) {
            ItemStack item = ItemStackUtil.normalize(source);
            if (item == null) {
                continue;
            }
            OverflowToken token = readToken(item);
            if (token != null && token.ownerUuid().equals(ownerUuid)) {
                entries.add(SourceEntry.overflow(token.id(), item));
                referencedIds.add(token.id());
            } else {
                entries.add(SourceEntry.item(item));
            }
        }

        Map<UUID, CompletableFuture<OverflowRecord>> loads = new HashMap<>();
        for (UUID id : referencedIds) {
            loads.put(id, storage.loadOverflow(id));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(loads.values().toArray(CompletableFuture[]::new));
        CompletableFuture<CreatedOverflow> result = new CompletableFuture<>();
        all.whenComplete((ignored, loadFailure) -> runSync(() -> {
            if (loadFailure != null) {
                result.completeExceptionally(loadFailure);
                return;
            }

            List<ItemStack> flattened = new ArrayList<>();
            Set<UUID> retired = new LinkedHashSet<>();
            try {
                for (SourceEntry entry : entries) {
                    if (entry.item() != null) {
                        flattened.add(entry.item().clone());
                        continue;
                    }

                    OverflowRecord record = loads.get(entry.overflowId()).join();
                    if (record == null || !record.ownerUuid().equals(ownerUuid)) {
                        // Keep the token if its backing record is unavailable. Losing an item is worse than nesting it.
                        flattened.add(entry.fallbackToken().clone());
                        continue;
                    }

                    ItemStack[] nested = InventoryCodec.deserializeExact(record.contents());
                    for (ItemStack nestedItem : nested) {
                        ItemStack normalized = ItemStackUtil.normalize(nestedItem);
                        if (normalized != null) {
                            flattened.add(normalized);
                        }
                    }
                    retired.add(record.id());
                }

                UUID id = UUID.randomUUID();
                OverflowRecord record = new OverflowRecord(
                        id,
                        ownerUuid,
                        System.currentTimeMillis(),
                        Math.max(1, Math.min(6, sourceRows)),
                        Math.max(1, Math.min(6, targetRows)),
                        InventoryCodec.serializeExact(ItemStackUtil.toArray(flattened))
                );

                storage.saveOverflow(record).whenComplete((unused, saveFailure) -> {
                    if (saveFailure != null) {
                        result.completeExceptionally(saveFailure);
                    } else {
                        runSync(() -> result.complete(new CreatedOverflow(
                                record,
                                createToken(record, flattened.size()),
                                List.copyOf(retired)
                        )));
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }));
        return result;
    }

    public CompletableFuture<Void> retire(List<UUID> overflowIds) {
        if (overflowIds == null || overflowIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] deletes = overflowIds.stream()
                .map(storage::deleteOverflow)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(deletes);
    }

    public boolean isOverflowToken(ItemStack item) {
        return readToken(item) != null;
    }

    public OverflowToken readToken(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST) {
            return null;
        }
        try {
            String idRaw = item.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            String ownerRaw = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            String version = item.getPersistentDataContainer().get(versionKey, PersistentDataType.STRING);
            String signature = item.getPersistentDataContainer().get(signatureKey, PersistentDataType.STRING);
            if (idRaw == null || ownerRaw == null || version == null || signature == null) {
                return null;
            }

            UUID id = UUID.fromString(idRaw);
            UUID owner = UUID.fromString(ownerRaw);
            if (!TOKEN_VERSION.equals(version) || !constantTimeEquals(signature, sign(id, owner, version))) {
                return null;
            }
            return new OverflowToken(id, owner);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public void redeem(Player player, ItemStack tokenItem) {
        OverflowToken token = readToken(tokenItem);
        if (token == null) {
            player.sendMessage(Component.text("That is not a valid LsEnderChest Overflow Chest.", NamedTextColor.RED));
            return;
        }
        if (!token.ownerUuid().equals(playerIds.getStorageUuid(player)) && !player.hasPermission("enderchest.admin")) {
            player.sendMessage(Component.text("That Overflow Chest belongs to another player.", NamedTextColor.RED));
            return;
        }
        if (!redeeming.add(token.id())) {
            player.sendMessage(Component.text("That Overflow Chest is already being claimed.", NamedTextColor.YELLOW));
            return;
        }

        ItemStack tokenBackup = tokenItem.clone();
        tokenBackup.setAmount(1);

        storage.loadOverflow(token.id()).whenComplete((record, failure) -> runSync(() -> {
            if (failure != null) {
                redeeming.remove(token.id());
                plugin.getLogger().severe("Could not load Overflow Chest " + token.id() + ": " + rootMessage(failure));
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Could not claim that Overflow Chest. Nothing was changed.", NamedTextColor.RED));
                }
                return;
            }
            if (record == null || !record.ownerUuid().equals(token.ownerUuid())) {
                redeeming.remove(token.id());
                if (player.isOnline()) {
                    player.sendMessage(Component.text("That Overflow Chest has already been claimed or no longer has valid backing data.", NamedTextColor.RED));
                }
                return;
            }

            final ItemStack[] contents;
            try {
                contents = InventoryCodec.deserializeExact(record.contents());
            } catch (RuntimeException exception) {
                redeeming.remove(token.id());
                plugin.getLogger().severe("Could not decode Overflow Chest " + token.id() + ": " + exception.getMessage());
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Could not decode that Overflow Chest. Nothing was changed.", NamedTextColor.RED));
                }
                return;
            }

            if (!player.isOnline() || !consumeToken(player, token.id())) {
                redeeming.remove(token.id());
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Keep the Overflow Chest in your inventory until it finishes claiming.", NamedTextColor.YELLOW));
                }
                return;
            }

            Location payoutLocation = player.getLocation().clone();
            storage.deleteOverflow(token.id()).whenComplete((deleted, deleteFailure) -> runSync(() -> {
                redeeming.remove(token.id());

                if (deleteFailure != null) {
                    plugin.getLogger().severe("Could not retire Overflow Chest " + token.id() + ": " + rootMessage(deleteFailure));
                    restoreToken(player, payoutLocation, tokenBackup);
                    if (player.isOnline()) {
                        player.sendMessage(Component.text("Could not claim that Overflow Chest. The token was returned.", NamedTextColor.RED));
                    }
                    return;
                }
                if (!Boolean.TRUE.equals(deleted)) {
                    // Another claim (including another server sharing MySQL) won the delete race.
                    // The consumed duplicate token intentionally stays consumed and pays out nothing.
                    if (player.isOnline()) {
                        player.sendMessage(Component.text("That Overflow Chest was already claimed.", NamedTextColor.RED));
                    }
                    return;
                }

                Location dropLocation = player.isOnline() ? player.getLocation() : payoutLocation;
                int droppedStacks = 0;
                for (ItemStack raw : contents) {
                    ItemStack item = ItemStackUtil.normalize(raw);
                    if (item == null) {
                        continue;
                    }
                    dropLocation.getWorld().dropItemNaturally(dropLocation, item.clone());
                    droppedStacks++;
                }

                plugin.getLogManager().logOverflowRedeem(record.ownerUuid(), player, record.id(), droppedStacks);
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Overflow Chest claimed — " + droppedStacks + " item stack"
                            + (droppedStacks == 1 ? " was" : "s were") + " dropped on the ground.", NamedTextColor.GREEN));
                }
            }));
        }));
    }

    private boolean consumeToken(Player player, UUID overflowId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            OverflowToken token = readToken(item);
            if (token == null || !token.id().equals(overflowId)) {
                continue;
            }

            if (item.getAmount() <= 1) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item);
            }
            return true;
        }
        return false;
    }

    private void restoreToken(Player player, Location fallbackLocation, ItemStack token) {
        if (player.isOnline()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(token.clone());
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            return;
        }
        fallbackLocation.getWorld().dropItemNaturally(fallbackLocation, token.clone());
    }

    public CompletableFuture<List<OverflowRecord>> captureReferences(ItemStack[] chestContents) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (chestContents != null) {
            for (ItemStack item : chestContents) {
                OverflowToken token = readToken(item);
                if (token != null) {
                    ids.add(token.id());
                }
            }
        }
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<OverflowRecord>> futures = ids.stream()
                .map(storage::loadOverflow)
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(record -> record != null)
                        .toList());
    }

    public CompletableFuture<RestoredOverflowTokens> cloneEmbeddedOverflows(UUID ownerUuid,
                                                                             ItemStack[] chestContents,
                                                                             List<OverflowRecord> embedded) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<RestoredOverflowTokens> future = new CompletableFuture<>();
            runSync(() -> cloneEmbeddedOverflows(ownerUuid, chestContents, embedded)
                    .whenComplete((value, failure) -> completeForward(future, value, failure)));
            return future;
        }

        Map<UUID, OverflowRecord> byOldId = new HashMap<>();
        for (OverflowRecord record : embedded) {
            if (record != null && record.ownerUuid().equals(ownerUuid)) {
                byOldId.put(record.id(), record);
            }
        }

        ItemStack[] restored = ItemStackUtil.cloneArray(chestContents, 54);
        Map<UUID, OverflowRecord> replacements = new HashMap<>();
        List<CompletableFuture<Void>> saves = new ArrayList<>();

        for (int i = 0; i < restored.length; i++) {
            OverflowToken token = readToken(restored[i]);
            if (token == null) {
                continue;
            }
            OverflowRecord historical = byOldId.get(token.id());
            if (historical == null) {
                continue;
            }

            OverflowRecord replacement = replacements.computeIfAbsent(token.id(), oldId -> {
                OverflowRecord record = new OverflowRecord(
                        UUID.randomUUID(), ownerUuid, System.currentTimeMillis(),
                        historical.sourceRows(), historical.targetRows(), historical.contents()
                );
                saves.add(storage.saveOverflow(record));
                return record;
            });

            int itemCount;
            try {
                itemCount = ItemStackUtil.countNonEmpty(InventoryCodec.deserializeExact(replacement.contents()));
            } catch (RuntimeException ignored) {
                itemCount = 0;
            }
            restored[i] = createToken(replacement, itemCount);
        }

        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new RestoredOverflowTokens(restored, replacements.values().stream().map(OverflowRecord::id).toList()));
    }

    public void shutdown() {
        redeeming.clear();
    }

    private ItemStack createToken(OverflowRecord record, int itemCount) {
        ItemStack item = ItemStack.of(Material.CHEST, 1);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Overflow Chest", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            String created = DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt()));
            List<Component> lore = plugin.getConfigManager().getOverflowChestLore().stream()
                    .map(line -> line
                            .replace("%items%", Integer.toString(itemCount))
                            .replace("%created%", created)
                            .replace("%source_rows%", Integer.toString(record.sourceRows()))
                            .replace("%target_rows%", Integer.toString(record.targetRows())))
                    .map(TextUtil::component)
                    .map(component -> component.decoration(TextDecoration.ITALIC, false))
                    .toList();
            meta.lore(lore);
        });
        item.editPersistentDataContainer(pdc -> {
            pdc.set(idKey, PersistentDataType.STRING, record.id().toString());
            pdc.set(ownerKey, PersistentDataType.STRING, record.ownerUuid().toString());
            pdc.set(versionKey, PersistentDataType.STRING, TOKEN_VERSION);
            pdc.set(signatureKey, PersistentDataType.STRING, sign(record.id(), record.ownerUuid(), TOKEN_VERSION));
        });
        return item;
    }

    private byte[] loadOrCreateSecret() {
        Path path = plugin.getInternalDataPath("overflow-secret.key");
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.UTF_8).trim());
                if (decoded.length >= 32) {
                    return decoded;
                }
            }

            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            Files.writeString(path, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
            return generated;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load/create overflow security key.", exception);
        }
    }

    private String sign(UUID id, UUID owner, String version) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((id + "|" + owner + "|" + version).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign Overflow Chest token.", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
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

    private static <T> void completeForward(CompletableFuture<T> target, T value, Throwable failure) {
        if (failure != null) {
            target.completeExceptionally(failure);
        } else {
            target.complete(value);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record OverflowToken(UUID id, UUID ownerUuid) {
    }

    public record CreatedOverflow(OverflowRecord record, ItemStack token, List<UUID> retiredOverflowIds) {
    }

    public record RestoredOverflowTokens(ItemStack[] chestContents, List<UUID> createdOverflowIds) {
    }

    private record SourceEntry(ItemStack item, UUID overflowId, ItemStack fallbackToken) {
        static SourceEntry item(ItemStack item) {
            return new SourceEntry(item, null, null);
        }

        static SourceEntry overflow(UUID id, ItemStack fallback) {
            return new SourceEntry(null, id, fallback);
        }
    }
}
