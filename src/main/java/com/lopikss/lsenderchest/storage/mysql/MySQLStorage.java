package com.lopikss.lsenderchest.storage.mysql;

import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.overflow.OverflowRecord;
import com.lopikss.lsenderchest.storage.StorageProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

public final class MySQLStorage implements StorageProvider {

    private final ConfigManager config;
    private Connection connection;

    public MySQLStorage(ConfigManager config) {
        this.config = config;
    }

    @Override
    public void init() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        ensureConnection();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ender_chests (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(16) NOT NULL,
                        contents LONGTEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS overflow_chests (
                        overflow_id VARCHAR(36) PRIMARY KEY,
                        owner_uuid VARCHAR(36) NOT NULL,
                        created_at BIGINT NOT NULL,
                        source_rows INT NOT NULL,
                        target_rows INT NOT NULL,
                        contents LONGTEXT NOT NULL,
                        INDEX idx_overflow_owner (owner_uuid)
                    )
                    """);
        }
    }

    @Override
    public String load(UUID playerUuid) throws Exception {
        ensureConnection();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT contents FROM ender_chests WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("contents") : null;
            }
        }
    }

    @Override
    public void save(UUID playerUuid, String playerName, String contents) throws Exception {
        ensureConnection();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ender_chests (player_uuid, player_name, contents)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE player_name = ?, contents = ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, contents);
            statement.setString(4, playerName);
            statement.setString(5, contents);
            statement.executeUpdate();
        }
    }

    @Override
    public OverflowRecord loadOverflow(UUID overflowId) throws Exception {
        ensureConnection();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, created_at, source_rows, target_rows, contents
                FROM overflow_chests
                WHERE overflow_id = ?
                """)) {
            statement.setString(1, overflowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new OverflowRecord(
                        overflowId,
                        UUID.fromString(result.getString("owner_uuid")),
                        result.getLong("created_at"),
                        result.getInt("source_rows"),
                        result.getInt("target_rows"),
                        result.getString("contents")
                );
            }
        }
    }

    @Override
    public void saveOverflow(OverflowRecord record) throws Exception {
        ensureConnection();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO overflow_chests (overflow_id, owner_uuid, created_at, source_rows, target_rows, contents)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner_uuid = ?,
                    created_at = ?,
                    source_rows = ?,
                    target_rows = ?,
                    contents = ?
                """)) {
            statement.setString(1, record.id().toString());
            statement.setString(2, record.ownerUuid().toString());
            statement.setLong(3, record.createdAt());
            statement.setInt(4, record.sourceRows());
            statement.setInt(5, record.targetRows());
            statement.setString(6, record.contents());
            statement.setString(7, record.ownerUuid().toString());
            statement.setLong(8, record.createdAt());
            statement.setInt(9, record.sourceRows());
            statement.setInt(10, record.targetRows());
            statement.setString(11, record.contents());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean deleteOverflow(UUID overflowId) throws Exception {
        ensureConnection();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM overflow_chests WHERE overflow_id = ?")) {
            statement.setString(1, overflowId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private void ensureConnection() throws Exception {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) {
            return;
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }

        String url = "jdbc:mysql://" + config.getMySQLHost() + ":" + config.getMySQLPort()
                + "/" + config.getMySQLDatabase() + config.getMySQLParameters();
        connection = DriverManager.getConnection(url, config.getMySQLUsername(), config.getMySQLPassword());
    }
}
