package com.lopikss.lsenderchest.conversion;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ConvertedPlayersStore {

    private final LsEnderChestPlugin plugin;
    private final File file;
    private final Set<String> converted = new HashSet<>();

    public ConvertedPlayersStore(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getInternalDataPath("converted-players.yml").toFile();
        reload();
    }

    public synchronized void reload() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        converted.clear();
        converted.addAll(config.getStringList("converted"));
    }

    public synchronized boolean isConverted(UUID uuid) {
        return converted.contains(uuid.toString());
    }

    public synchronized void markConverted(UUID uuid) {
        if (!converted.add(uuid.toString())) {
            return;
        }
        save();
    }

    private void save() {
        FileConfiguration config = new YamlConfiguration();
        config.set("converted", converted.stream().sorted().toList());
        try {
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save converted-players.yml: " + exception.getMessage());
        }
    }
}
