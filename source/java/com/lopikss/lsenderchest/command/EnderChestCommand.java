package com.lopikss.lsenderchest.command;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.util.ColorUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final LsEnderChestPlugin plugin;
    private final ChestManager chestManager;

    public EnderChestCommand(LsEnderChestPlugin plugin, ChestManager chestManager) {
        this.plugin = plugin;
        this.chestManager = chestManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }

        if (args.length == 0) {
            if (!chestManager.canUse(player)) {
                chestManager.sendNoPermission(player);
                return true;
            }

            chestManager.openOwnChest(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("enderchest.admin")) {
                chestManager.sendNoPermission(player);
                return true;
            }

            chestManager.reload();
            plugin.reloadBlockedItems();

            player.sendMessage(ColorUtil.color(
                    plugin.getConfigManager().getMessage("reloaded", "&aConfiguration reloaded.")
            ));
            return true;
        }

        if (!player.hasPermission("enderchest.admin")
                && !player.hasPermission("enderchest.view.other")) {
            chestManager.sendNoPermission(player);
            return true;
        }

        OfflinePlayer target = chestManager.findTarget(args[0]);
        if (target == null || target.getName() == null) {
            chestManager.sendPlayerNotFound(player);
            return true;
        }

        chestManager.openOtherChest(player, target);
        chestManager.sendOpenedOther(player, target.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }

        if (!sender.hasPermission("enderchest.admin")) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();
        suggestions.add("reload");

        boolean includeOfflinePlayers = plugin.getConfig().getBoolean("settings.tab-complete-offline-players", true);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName() != null && !player.getName().isBlank()) {
                suggestions.add(player.getName());
            }
        }

        if (includeOfflinePlayers) {
            for (OfflinePlayer offlinePlayer : plugin.getServer().getOfflinePlayers()) {
                String name = offlinePlayer.getName();
                if (name != null && !name.isBlank()) {
                    suggestions.add(name);
                }
            }
        }

        String input = args[0].toLowerCase();

        return suggestions.stream()
                .filter(value -> value.toLowerCase().startsWith(input))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}