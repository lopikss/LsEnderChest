package com.lopikss.lsenderchest.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

public final class PlayerIdManager {

    private final boolean onlineMode;

    public PlayerIdManager(boolean onlineMode) {
        this.onlineMode = onlineMode;
    }

    public UUID getStorageUuid(OfflinePlayer player) {
        if (onlineMode) {
            return player.getUniqueId();
        }

        String name = player.getName();
        if (name == null || name.isBlank()) {
            return player.getUniqueId();
        }
        return offlineUuid(name);
    }

    public Optional<PlayerIdentity> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new PlayerIdentity(getStorageUuid(online), online.getName()));
        }

        if (!onlineMode) {
            return Optional.of(new PlayerIdentity(offlineUuid(name), name));
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return Optional.of(new PlayerIdentity(cached.getUniqueId(), cached.getName()));
        }

        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String playerName = player.getName();
            if (playerName != null && playerName.equalsIgnoreCase(name)) {
                return Optional.of(new PlayerIdentity(player.getUniqueId(), playerName));
            }
        }

        return Optional.empty();
    }

    private static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    public record PlayerIdentity(UUID uuid, String name) {
    }
}
