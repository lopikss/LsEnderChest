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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogManager {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LsEnderChestPlugin plugin;
    private final Path simpleLogFile;
    private final Path detailedLogFile;

    public LogManager(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
        this.simpleLogFile = plugin.getDataFolder().toPath().resolve("logs").resolve("simple.log");
        this.detailedLogFile = plugin.getDataFolder().toPath().resolve("logs").resolve("detailed.log");

        createFile(simpleLogFile);
        createFile(detailedLogFile);
    }

    public void logOpen(Player viewer, String ownerName) {
        if (!isDetailedLoggingEnabled()) {
            return;
        }

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=OPEN "
                        + "VIEWER=" + sanitize(viewer.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "MODE=UNKNOWN "
                        + "SOURCE=UNKNOWN "
                        + formatLocation(viewer.getLocation())
        );
    }

    public void logAdd(Player player, ItemStack item) {
        if (!isSimpleLoggingEnabled() || isInvalidItem(item)) {
            return;
        }

        write(
                simpleLogFile,
                "+ " + sanitize(player.getName()) + " "
                        + sanitize(item.getType().name()) + " x" + item.getAmount()
                        + " [" + now() + "]"
        );
    }

    public void logRemove(Player player, ItemStack item) {
        if (!isSimpleLoggingEnabled() || isInvalidItem(item)) {
            return;
        }

        write(
                simpleLogFile,
                "- " + sanitize(player.getName()) + " "
                        + sanitize(item.getType().name()) + " x" + item.getAmount()
                        + " [" + now() + "]"
        );
    }

    public void logMove(Player player, String ownerName, ItemStack item) {
        logAdd(player, item);
    }

    public void logOpenDetailed(Player viewer, String ownerName, String mode, String source, Location location) {
        if (!isDetailedLoggingEnabled()) {
            return;
        }

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=OPEN "
                        + "VIEWER=" + sanitize(viewer.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "MODE=" + sanitize(mode) + " "
                        + "SOURCE=" + sanitize(source) + " "
                        + formatLocation(location)
        );
    }

    public void logMoveDetailed(Player player,
                                String ownerName,
                                ItemStack item,
                                String action,
                                String clickType,
                                String inventoryAction,
                                Location location) {
        if (!isDetailedLoggingEnabled() || isInvalidItem(item)) {
            return;
        }

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=" + sanitize(action) + " "
                        + "VIEWER=" + sanitize(player.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "ITEM=" + sanitize(item.getType().name()) + " "
                        + "AMOUNT=" + item.getAmount() + " "
                        + "CLICK=" + sanitize(clickType) + " "
                        + "INV_ACTION=" + sanitize(inventoryAction) + " "
                        + formatLocation(location)
        );
    }

    public void logRemoveDetailed(Player player,
                                  String ownerName,
                                  ItemStack item,
                                  String clickType,
                                  String inventoryAction,
                                  Location location) {
        if (!isDetailedLoggingEnabled() || isInvalidItem(item)) {
            return;
        }

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=REMOVE "
                        + "VIEWER=" + sanitize(player.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "ITEM=" + sanitize(item.getType().name()) + " "
                        + "AMOUNT=" + item.getAmount() + " "
                        + "CLICK=" + sanitize(clickType) + " "
                        + "INV_ACTION=" + sanitize(inventoryAction) + " "
                        + formatLocation(location)
        );
    }

    public void logBlockedAttempt(Player player,
                                  String ownerName,
                                  ItemStack item,
                                  String clickType,
                                  String inventoryAction,
                                  Location location) {
        if (!isDetailedLoggingEnabled()) {
            return;
        }

        String itemName = item == null || item.getType() == Material.AIR ? "AIR" : item.getType().name();
        int amount = item == null ? 0 : item.getAmount();

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=BLOCKED_ATTEMPT "
                        + "VIEWER=" + sanitize(player.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "ITEM=" + sanitize(itemName) + " "
                        + "AMOUNT=" + amount + " "
                        + "CLICK=" + sanitize(clickType) + " "
                        + "INV_ACTION=" + sanitize(inventoryAction) + " "
                        + formatLocation(location)
        );
    }

    public void logReadOnlyAttempt(Player player,
                                   String ownerName,
                                   String clickType,
                                   String inventoryAction,
                                   Location location) {
        if (!isDetailedLoggingEnabled()) {
            return;
        }

        write(
                detailedLogFile,
                "[" + now() + "] "
                        + "ACTION=READ_ONLY_ATTEMPT "
                        + "VIEWER=" + sanitize(player.getName()) + " "
                        + "OWNER=" + sanitize(ownerName) + " "
                        + "CLICK=" + sanitize(clickType) + " "
                        + "INV_ACTION=" + sanitize(inventoryAction) + " "
                        + formatLocation(location)
        );
    }

    private boolean isSimpleLoggingEnabled() {
        return plugin.getConfig().getBoolean("logging.simple", true);
    }

    private boolean isDetailedLoggingEnabled() {
        return plugin.getConfig().getBoolean("logging.detailed", true);
    }

    private boolean isInvalidItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private void createFile(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.createFile(path);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create log file " + path.getFileName() + ": " + exception.getMessage());
        }
    }

    private String now() {
        return LocalDateTime.now().format(FORMAT);
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "WORLD=unknown X=0 Y=0 Z=0";
        }

        return "WORLD=" + sanitize(location.getWorld().getName())
                + " X=" + location.getBlockX()
                + " Y=" + location.getBlockY()
                + " Z=" + location.getBlockZ();
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        return value.replace(" ", "_");
    }

    private void write(Path file, String line) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(
                        file,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write log entry to " + file.getFileName() + ": " + exception.getMessage());
            }
        });
    }
}