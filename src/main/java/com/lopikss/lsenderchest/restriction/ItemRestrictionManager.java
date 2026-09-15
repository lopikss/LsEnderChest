package com.lopikss.lsenderchest.restriction;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.util.TextUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ItemRestrictionManager {

    private enum Mode {
        BLACKLIST,
        WHITELIST
    }

    private final LsEnderChestPlugin plugin;
    private final Set<Material> materials = new HashSet<>();
    private final Set<String> loreLines = new HashSet<>();
    private boolean customModelData;
    private Mode mode = Mode.BLACKLIST;

    public ItemRestrictionManager(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "blocked-items.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        materials.clear();
        loreLines.clear();
        customModelData = false;

        String configuredMode = config.getString("mode", "BLACKLIST");
        try {
            mode = Mode.valueOf(configuredMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            mode = Mode.BLACKLIST;
            plugin.getLogger().warning("Invalid blocked-items.yml mode '" + configuredMode + "'. Using BLACKLIST.");
        }

        for (String rawName : config.getStringList("blocked-items")) {
            String materialName = rawName.trim().toUpperCase(Locale.ROOT);
            if (materialName.equals("ENDER_DRAGON_EGG")) {
                materialName = "DRAGON_EGG"; // compatibility with the 1.1.0 default config typo
            }

            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("Invalid material in blocked-items.yml: " + rawName);
                continue;
            }
            materials.add(material);
        }

        for (String lore : config.getStringList("blocked-lore")) {
            String normalized = TextUtil.normalizeLegacy(lore);
            if (!normalized.isBlank()) {
                loreLines.add(normalized);
            }
        }

        List<String> components = config.contains("blocked-components")
                ? config.getStringList("blocked-components")
                : config.getStringList("blocked-nbt");

        for (String component : components) {
            String normalized = component.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (normalized.equals("custommodeldata")) {
                customModelData = true;
            } else if (!normalized.isBlank()) {
                plugin.getLogger().warning("Unsupported blocked component in blocked-items.yml: " + component);
            }
        }
    }

    public boolean isBlocked(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        boolean matches = matchesRestriction(item);
        return mode == Mode.BLACKLIST ? matches : !matches;
    }

    private boolean matchesRestriction(ItemStack item) {
        if (materials.contains(item.getType())) {
            return true;
        }

        if (customModelData && item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore();
        if (lore != null) {
            for (Component line : lore) {
                if (loreLines.contains(TextUtil.normalizeComponent(line))) {
                    return true;
                }
            }
        }

        return false;
    }
}
