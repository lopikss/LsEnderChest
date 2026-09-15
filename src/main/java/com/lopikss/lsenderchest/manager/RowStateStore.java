package com.lopikss.lsenderchest.manager;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RowStateStore {

    private final LsEnderChestPlugin plugin;
    private final File file;
    private final Map<UUID, Integer> rows = new HashMap<>();

    public RowStateStore(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getInternalDataPath("row-state.yml").toFile();
        reload();
    }

    public synchronized void reload() {
        rows.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                int value = Math.max(1, Math.min(6, config.getInt(key, 1)));
                rows.put(uuid, value);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid UUID in row-state.yml: " + key);
            }
        }
    }

    public synchronized Integer get(UUID uuid) {
        return rows.get(uuid);
    }

    public synchronized void set(UUID uuid, int value) {
        int normalized = Math.max(1, Math.min(6, value));
        Integer previous = rows.put(uuid, normalized);
        if (previous != null && previous == normalized) {
            return;
        }
        save();
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        rows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.set(entry.getKey().toString(), entry.getValue()));
        try {
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save row-state.yml: " + exception.getMessage());
        }
    }
}
