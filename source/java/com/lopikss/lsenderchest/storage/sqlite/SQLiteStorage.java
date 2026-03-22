package com.lopikss.lsenderchest.storage.sqlite;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.storage.StorageProvider;
import com.lopikss.lsenderchest.util.InventorySerializer;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;

import java.util.UUID;

public class SQLiteStorage implements StorageProvider {

    private final LsEnderChestPlugin plugin;
    private final ConfigManager configManager;
    private Connection connection;

    public SQLiteStorage(LsEnderChestPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void init() {
        try {
            File file = new File(plugin.getDataFolder(), configManager.getSQLiteFile());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enderchests (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        contents TEXT NOT NULL
                    )
                    """);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to initialize SQLite storage.", exception);
        }
    }

    @Override
    public ItemStack[] load(UUID playerUuid) {
        String sql = "SELECT contents FROM enderchests WHERE player_uuid = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return InventorySerializer.deserialize(resultSet.getString("contents"));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to load chest from SQLite.", exception);
        }

        return new ItemStack[54];
    }

    @Override
    public void save(UUID playerUuid, String playerName, ItemStack[] contents) {
        String sql = """
            INSERT INTO enderchests (player_uuid, player_name, contents)
            VALUES (?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                player_name = excluded.player_name,
                contents = excluded.contents
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName == null ? "unknown" : playerName);
            statement.setString(3, InventorySerializer.serialize(contents));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to save chest to SQLite.", exception);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}