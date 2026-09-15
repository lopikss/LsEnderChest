package com.lopikss.lsenderchest.log;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LogManager {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final LsEnderChestPlugin plugin;
    private final Path playerLogFolder;
    private final ExecutorService executor;

    public LogManager(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
        this.playerLogFolder = plugin.getDataFolder().toPath().resolve("logs").resolve("players");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LsEnderChest-AuditLog");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.createDirectories(playerLogFolder);
            archiveLegacyLogs();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create per-player log directory.", exception);
        }
    }

    public void logOpen(UUID ownerUuid,
                        String ownerName,
                        UUID sessionId,
                        Player viewer,
                        String mode,
                        String source,
                        Location location) {
        event(ownerUuid, sessionId, "OPEN",
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(viewer),
                "MODE=" + safe(mode),
                "SOURCE=" + safe(source),
                formatLocation(location));
    }

    public void logClose(UUID ownerUuid,
                         String ownerName,
                         UUID sessionId,
                         Player viewer,
                         int viewersRemaining,
                         Location location) {
        event(ownerUuid, sessionId, "CLOSE",
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(viewer),
                "VIEWERS_REMAINING=" + viewersRemaining,
                formatLocation(location));
    }

    public void logBlockedAttempt(UUID ownerUuid,
                                  String ownerName,
                                  Player player,
                                  UUID sessionId,
                                  ItemStack item,
                                  String clickType,
                                  String inventoryAction,
                                  Location location) {
        String itemName = isInvalidItem(item) ? "AIR" : item.getType().name();
        int amount = isInvalidItem(item) ? 0 : item.getAmount();
        event(ownerUuid, sessionId, "BLOCKED_ATTEMPT",
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(player),
                "ITEM=" + safe(itemName),
                "AMOUNT=" + amount,
                "CLICK=" + safe(clickType),
                "INV_ACTION=" + safe(inventoryAction),
                formatLocation(location));
    }

    public void logReadOnlyAttempt(UUID ownerUuid,
                                   String ownerName,
                                   Player player,
                                   UUID sessionId,
                                   String clickType,
                                   String inventoryAction,
                                   Location location) {
        event(ownerUuid, sessionId, "READ_ONLY_ATTEMPT",
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(player),
                "CLICK=" + safe(clickType),
                "INV_ACTION=" + safe(inventoryAction),
                formatLocation(location));
    }

    public int logInventoryChanges(UUID ownerUuid,
                                   String ownerName,
                                   UUID sessionId,
                                   Player actor,
                                   ItemStack[] before,
                                   ItemStack[] after,
                                   Location location) {
        List<CountedItem> oldItems = aggregate(before);
        List<CountedItem> newItems = aggregate(after);
        int changes = 0;

        for (CountedItem oldItem : oldItems) {
            int delta = amountOf(newItems, oldItem.item()) - oldItem.amount();
            if (delta < 0) {
                logNetChange(ownerUuid, ownerName, sessionId, actor, oldItem.item(), -delta, false, location);
                changes++;
            }
        }
        for (CountedItem newItem : newItems) {
            int delta = newItem.amount() - amountOf(oldItems, newItem.item());
            if (delta > 0) {
                logNetChange(ownerUuid, ownerName, sessionId, actor, newItem.item(), delta, true, location);
                changes++;
            }
        }
        return changes;
    }

    public void logAdminAction(UUID ownerUuid,
                               String ownerName,
                               UUID sessionId,
                               Player admin,
                               String action,
                               String details) {
        event(ownerUuid, sessionId, "ADMIN_" + safe(action).toUpperCase(),
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(admin),
                "DETAILS=" + safe(details));
    }

    public void logSystem(UUID ownerUuid, UUID sessionId, String action, String details) {
        event(ownerUuid, sessionId, "SYSTEM_" + safe(action).toUpperCase(), "DETAILS=" + safe(details));
    }

    public void logOverflowRedeem(UUID ownerUuid, Player actor, UUID overflowId, int stacks) {
        event(ownerUuid, UUID.randomUUID(), "OVERFLOW_REDEEM",
                "ACTOR=" + actor(actor), "OVERFLOW=" + overflowId, "DROPPED_STACKS=" + stacks);
    }

    public CompletableFuture<Void> recordRestorePoint(UUID ownerUuid,
                                                        String ownerName,
                                                        UUID sessionId,
                                                        String kind,
                                                        int rows,
                                                        List<String> actors,
                                                        String summary,
                                                        String snapshotData) {
        String actorText = String.join(", ", actors == null ? List.of() : actors);
        String line = timestamp() + " | RESTORE_POINT"
                + " | SESSION=" + sessionId
                + " | OWNER=" + safe(ownerName)
                + " | KIND64=" + enc(kind)
                + " | ROWS=" + Math.max(1, Math.min(6, rows))
                + " | ACTORS64=" + enc(actorText)
                + " | SUMMARY64=" + enc(summary)
                + " | EPOCH=" + System.currentTimeMillis()
                + " | DATA=" + enc(snapshotData);
        return writeAsync(ownerUuid, line);
    }

    public CompletableFuture<List<RestorePoint>> loadRestorePoints(UUID ownerUuid) {
        CompletableFuture<List<RestorePoint>> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    Path file = file(ownerUuid);
                    if (Files.notExists(file)) {
                        future.complete(List.of());
                        return;
                    }

                    int max = plugin.getConfigManager().getRestoreHistoryLimit();
                    ArrayDeque<RestorePoint> recent = new ArrayDeque<>(max);
                    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                        lines.forEach(line -> {
                            if (!line.contains(" | RESTORE_POINT | ")) {
                                return;
                            }
                            RestorePoint point = parseRestorePoint(line);
                            if (point == null) {
                                return;
                            }
                            if (recent.size() == max) {
                                recent.removeFirst();
                            }
                            recent.addLast(point);
                        });
                    }

                    List<RestorePoint> points = new ArrayList<>(recent);
                    points.sort((left, right) -> Long.compare(right.timestamp(), left.timestamp()));
                    future.complete(List.copyOf(points));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public void shutdown(Duration timeout) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void archiveLegacyLogs() throws IOException {
        Path logsFolder = playerLogFolder.getParent();
        if (logsFolder == null) {
            return;
        }
        Path legacyFolder = logsFolder.resolve("legacy");
        archiveLegacyLog(logsFolder.resolve("simple.log"), legacyFolder.resolve("simple-pre-2.0.log"));
        archiveLegacyLog(logsFolder.resolve("detailed.log"), legacyFolder.resolve("detailed-pre-2.0.log"));
    }

    private void archiveLegacyLog(Path source, Path preferredTarget) throws IOException {
        if (Files.notExists(source)) {
            return;
        }
        Files.createDirectories(preferredTarget.getParent());
        Path target = preferredTarget;
        int suffix = 1;
        while (Files.exists(target)) {
            String name = preferredTarget.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot >= 0 ? name.substring(0, dot) : name;
            String extension = dot >= 0 ? name.substring(dot) : "";
            target = preferredTarget.getParent().resolve(base + "-" + suffix++ + extension);
        }
        Files.move(source, target);
        plugin.getLogger().info("Archived legacy audit log to " + target.getFileName() + ".");
    }

    private void logNetChange(UUID ownerUuid,
                              String ownerName,
                              UUID sessionId,
                              Player actor,
                              ItemStack template,
                              int amount,
                              boolean added,
                              Location location) {
        if (amount <= 0 || isInvalidItem(template)) {
            return;
        }
        event(ownerUuid, sessionId, added ? "ADD" : "REMOVE",
                "OWNER=" + safe(ownerName),
                "ACTOR=" + actor(actor),
                "ITEM=" + template.getType().name(),
                "AMOUNT=" + amount,
                formatLocation(location));
    }

    private void event(UUID ownerUuid, UUID sessionId, String action, String... fields) {
        if (!plugin.getConfigManager().isLoggingEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder(timestamp())
                .append(" | SESSION=").append(sessionId == null ? "none" : sessionId)
                .append(" | ACTION=").append(safe(action));
        for (String field : fields) {
            line.append(" | ").append(field);
        }
        write(ownerUuid, line.toString());
    }

    private void write(UUID ownerUuid, String line) {
        writeAsync(ownerUuid, line).exceptionally(failure -> null);
    }

    private CompletableFuture<Void> writeAsync(UUID ownerUuid, String line) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    Path file = file(ownerUuid);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    future.complete(null);
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not write player audit log for " + ownerUuid + ": " + exception.getMessage());
                    future.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    private RestorePoint parseRestorePoint(String line) {
        try {
            String[] parts = line.split(" \\| ");
            Map<String, String> fields = new LinkedHashMap<>();
            for (String part : parts) {
                int equals = part.indexOf('=');
                if (equals > 0) {
                    fields.put(part.substring(0, equals), part.substring(equals + 1));
                }
            }
            UUID sessionId = UUID.fromString(fields.get("SESSION"));
            long epoch = Long.parseLong(fields.get("EPOCH"));
            int rows = Integer.parseInt(fields.get("ROWS"));
            String ownerName = fields.getOrDefault("OWNER", "unknown");
            String kind = dec(fields.get("KIND64"));
            String actorsRaw = dec(fields.get("ACTORS64"));
            String summary = dec(fields.get("SUMMARY64"));
            String data = dec(fields.get("DATA"));
            List<String> actors = actorsRaw.isBlank() ? List.of() : List.of(actorsRaw.split(", "));
            return new RestorePoint(sessionId, epoch, kind, rows, ownerName, actors, summary, data);
        } catch (Exception exception) {
            plugin.getLogger().warning("Skipped an unreadable restore point in a player log: " + exception.getMessage());
            return null;
        }
    }

    private List<CountedItem> aggregate(ItemStack[] contents) {
        List<CountedItem> result = new ArrayList<>();
        if (contents == null) {
            return result;
        }

        for (ItemStack item : contents) {
            if (isInvalidItem(item)) {
                continue;
            }
            CountedItem existing = null;
            for (CountedItem candidate : result) {
                if (candidate.item().isSimilar(item)) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                result.add(new CountedItem(item.clone(), item.getAmount()));
            } else {
                existing.add(item.getAmount());
            }
        }
        return result;
    }

    private int amountOf(List<CountedItem> items, ItemStack template) {
        for (CountedItem item : items) {
            if (item.item().isSimilar(template)) {
                return item.amount();
            }
        }
        return 0;
    }

    private Path file(UUID ownerUuid) {
        return playerLogFolder.resolve(ownerUuid + ".log");
    }

    private String timestamp() {
        return LocalDateTime.now().format(DISPLAY_TIME);
    }

    private String actor(Player player) {
        return safe(player.getName()) + "(" + player.getUniqueId() + ")";
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "WORLD=unknown | X=0 | Y=0 | Z=0";
        }
        return "WORLD=" + safe(location.getWorld().getName())
                + " | X=" + location.getBlockX()
                + " | Y=" + location.getBlockY()
                + " | Z=" + location.getBlockZ();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[\\r\\n\\t|]+", "_").trim();
    }

    private boolean isInvalidItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getType().isAir() || item.getAmount() <= 0;
    }

    private String enc(String value) {
        return B64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String dec(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return new String(B64D.decode(value), StandardCharsets.UTF_8);
    }

    public record RestorePoint(
            UUID sessionId,
            long timestamp,
            String kind,
            int rows,
            String ownerName,
            List<String> actors,
            String summary,
            String snapshotData
    ) {
        public Instant instant() {
            return Instant.ofEpochMilli(timestamp);
        }
    }

    private static final class CountedItem {
        private final ItemStack item;
        private int amount;

        private CountedItem(ItemStack item, int amount) {
            this.item = item;
            this.amount = amount;
        }

        private ItemStack item() {
            return item;
        }

        private int amount() {
            return amount;
        }

        private void add(int amount) {
            this.amount += amount;
        }
    }
}
