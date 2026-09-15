package com.lopikss.lsenderchest.storage.sqlite;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.config.ConfigManager;
import com.lopikss.lsenderchest.overflow.OverflowRecord;
import com.lopikss.lsenderchest.storage.StorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

public final class SQLiteStorage implements StorageProvider {

    private final LsEnderChestPlugin plugin;
    private final ConfigManager config;
    private Connection connection;

    public SQLiteStorage(LsEnderChestPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");

        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path databaseFile = dataFolder.resolve(config.getSQLiteFile()).normalize();
        if (!databaseFile.startsWith(dataFolder)) {
            throw new IllegalArgumentException("storage.sqlite.file must stay inside the LsEnderChest plugin folder.");
        }

        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ender_chests (
                        player_uuid TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        contents TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS overflow_chests (
                        overflow_id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        source_rows INTEGER NOT NULL,
                        target_rows INTEGER NOT NULL,
                        contents TEXT NOT NULL
                    )
                    """);
        }
    }

    @Override
    public String load(UUID playerUuid) throws Exception {
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
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ender_chests (player_uuid, player_name, contents)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    contents = excluded.contents
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, contents);
            statement.executeUpdate();
        }
    }

    @Override
    public OverflowRecord loadOverflow(UUID overflowId) throws Exception {
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
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO overflow_chests (overflow_id, owner_uuid, created_at, source_rows, target_rows, contents)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(overflow_id) DO UPDATE SET
                    owner_uuid = excluded.owner_uuid,
                    created_at = excluded.created_at,
                    source_rows = excluded.source_rows,
                    target_rows = excluded.target_rows,
                    contents = excluded.contents
                """)) {
            statement.setString(1, record.id().toString());
            statement.setString(2, record.ownerUuid().toString());
            statement.setLong(3, record.createdAt());
            statement.setInt(4, record.sourceRows());
            statement.setInt(5, record.targetRows());
            statement.setString(6, record.contents());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean deleteOverflow(UUID overflowId) throws Exception {
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
}
