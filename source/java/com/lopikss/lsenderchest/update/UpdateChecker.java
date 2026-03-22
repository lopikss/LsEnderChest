package com.lopikss.lsenderchest.update;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class UpdateChecker {

    private static final String VERSION_URL = "https://lsplugins.com/UpdateNotefier/LsEnderChest.txt";

    private final LsEnderChestPlugin plugin;

    private boolean updateAvailable = false;
    private String latestVersion = null;

    public UpdateChecker(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkNow() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URI uri = URI.create(VERSION_URL);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(uri.toURL().openStream(), StandardCharsets.UTF_8))) {

                    String fetchedVersion = reader.readLine();

                    if (fetchedVersion == null || fetchedVersion.isBlank()) {
                        return;
                    }

                    fetchedVersion = fetchedVersion.trim();

                    String currentVersion = plugin.getDescription().getVersion();

                    if (isNewerVersion(currentVersion, fetchedVersion)) {
                        updateAvailable = true;
                        latestVersion = fetchedVersion;

                        plugin.getLogger().info("New version available: " + fetchedVersion + " (current: " + currentVersion + ")");
                    }
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not check for updates: " + exception.getMessage());
            }
        });
    }

    public void notifyPlayer(Player player) {

        if (!updateAvailable) return;

        if (!player.isOp() && !player.hasPermission("enderchest.admin")) return;

        String currentVersion = plugin.getDescription().getVersion();

        player.sendMessage(Component.text("------------------------------------------------", NamedTextColor.DARK_GRAY));

        player.sendMessage(
                Component.text("[LsEnderChest] ", NamedTextColor.GRAY)
                        .append(Component.text("Update Available", NamedTextColor.YELLOW))
        );

        player.sendMessage(Component.empty());

        player.sendMessage(
                Component.text("A new version of ", NamedTextColor.GRAY)
                        .append(Component.text("LsEnderChest", NamedTextColor.AQUA))
                        .append(Component.text(" is available.", NamedTextColor.GRAY))
        );

        player.sendMessage(
                Component.text("Latest Version: ", NamedTextColor.GRAY)
                        .append(Component.text(latestVersion, NamedTextColor.GREEN))
        );

        player.sendMessage(
                Component.text("Your Version: ", NamedTextColor.GRAY)
                        .append(Component.text(currentVersion, NamedTextColor.RED))
        );

        player.sendMessage(Component.empty());

        Component download = Component.text("Download on Modrinth", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl("https://modrinth.com/plugin/lsenderchest"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open the Modrinth page")));

        player.sendMessage(download);

        player.sendMessage(Component.text("------------------------------------------------", NamedTextColor.DARK_GRAY));
    }

    private boolean isNewerVersion(String current, String latest) {
        String[] currentParts = current.split("\\.");
        String[] latestParts = latest.split("\\.");

        int maxLength = Math.max(currentParts.length, latestParts.length);

        for (int i = 0; i < maxLength; i++) {
            int currentPart = i < currentParts.length ? parsePart(currentParts[i]) : 0;
            int latestPart = i < latestParts.length ? parsePart(latestParts[i]) : 0;

            if (latestPart > currentPart) return true;
            if (latestPart < currentPart) return false;
        }

        return false;
    }

    private int parsePart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (Exception exception) {
            return 0;
        }
    }
}