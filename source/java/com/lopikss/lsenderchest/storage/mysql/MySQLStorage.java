package com.lopikss.lsenderchest.storage.mysql;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.storage.StorageProvider;
import com.lopikss.lsenderchest.util.InventorySerializer;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.UUID;

public class MySQLStorage implements StorageProvider {

    private final LsEnderChestPlugin plugin;
    private final ConfigManager configManager;
    private Connection connection;

    public MySQLStorage(LsEnderChestPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void init() {
        try {
            String url = "jdbc:mysql://" + configManager.getMySQLHost() + ":" + configManager.getMySQLPort()
                    + "/" + configManager.getMySQLDatabase() + configManager.getMySQLParameters();

            connection = DriverManager.getConnection(
                    url,
                    configManager.getMySQLUsername(),
                    configManager.getMySQLPassword()
            );

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS enderchests (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(16) NOT NULL,
                        contents LONGTEXT NOT NULL
                    )
                    """);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to initialize MySQL storage.", exception);
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
            throw new RuntimeException("Failed to load chest from MySQL.", exception);
        }

        return new ItemStack[54];
    }

    @Override
    public void save(UUID playerUuid, String playerName, ItemStack[] contents) {
        String sql = """
            INSERT INTO enderchests (player_uuid, player_name, contents)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                contents = VALUES(contents)
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName == null ? "unknown" : playerName);
            statement.setString(3, InventorySerializer.serialize(contents));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to save chest to MySQL.", exception);
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