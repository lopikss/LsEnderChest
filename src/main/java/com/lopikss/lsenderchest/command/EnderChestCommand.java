package com.lopikss.lsenderchest.command;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.conversion.ConversionManager;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.manager.PlayerIdManager;
import com.lopikss.lsenderchest.restore.RestoreManager;
import com.lopikss.lsenderchest.util.TextUtil;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class EnderChestCommand implements BasicCommand {

    private final LsEnderChestPlugin plugin;
    private final ChestManager chests;
    private final ConversionManager conversions;
    private final RestoreManager restores;
    private final Map<String, PendingConfirmation> confirmations = new HashMap<>();

    public EnderChestCommand(LsEnderChestPlugin plugin,
                             ChestManager chests,
                             ConversionManager conversions,
                             RestoreManager restores) {
        this.plugin = plugin;
        this.chests = chests;
        this.conversions = conversions;
        this.restores = restores;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            handleConfirm(sender);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("convert")) {
            handleConvert(sender, args);
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("restore")) {
            handleRestore(sender, args);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("enderchest.admin")) {
                sendNoPermission(sender);
                return;
            }

            boolean restartRequired = plugin.reloadPluginConfiguration();
            sender.sendMessage(TextUtil.component(plugin.getConfigManager().getMessage(
                    "reloaded", "&aConfiguration reloaded.")));
            if (restartRequired) {
                sender.sendMessage(TextUtil.component(plugin.getConfigManager().getMessage(
                        "restart-required", "&eStorage or UUID settings changed and require a server restart.")));
            }
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextUtil.component(
                    "&cConsole can use reload, convert and confirm. Opening/restoring chests requires an in-game admin."));
            return;
        }

        if (args.length == 0) {
            chests.openOwnChest(player);
            return;
        }

        if (args.length == 1) {
            chests.openOtherChest(player, args[0]);
            return;
        }

        sendUsage(sender);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission("enderchest.admin")) {
                suggestions.add("reload");
                suggestions.add("restore");
            }
            if (sender.hasPermission("enderchest.convert")) {
                suggestions.add("convert");
            }
            if (hasValidConfirmation(sender)) {
                suggestions.add("confirm");
            }

            if (sender.hasPermission("enderchest.admin") || sender.hasPermission("enderchest.view.other")) {
                addKnownPlayers(suggestions);
            }
            return filterPrefix(suggestions, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("convert")
                && sender.hasPermission("enderchest.convert")) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
            return filterPrefix(suggestions, args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("restore")
                && sender.hasPermission("enderchest.admin")) {
            List<String> suggestions = new ArrayList<>();
            addKnownPlayers(suggestions);
            return filterPrefix(suggestions, args[1]);
        }

        return List.of();
    }

    private void handleRestore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enderchest.admin")) {
            sendNoPermission(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextUtil.component("&c/ec restore uses a GUI and must be run in-game."));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(TextUtil.component("&cUsage: /ec restore <player>"));
            return;
        }

        PlayerIdManager.PlayerIdentity target = chests.resolveIdentity(args[1]);
        if (target == null) {
            chests.sendPlayerNotFound(player);
            return;
        }
        restores.openHistory(player, target);
    }

    private void handleConvert(CommandSender sender, String[] args) {
        if (!sender.hasPermission("enderchest.convert")) {
            sendNoPermission(sender);
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(TextUtil.component("&cUsage: /ec convert <player|all>"));
            return;
        }

        if (args[1].equalsIgnoreCase("all")) {
            armConvertAll(sender);
            return;
        }

        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(TextUtil.component("&cThat player must be online to convert their vanilla Ender Chest."));
            return;
        }

        sender.sendMessage(TextUtil.component("&eChecking &f" + target.getName() + "&e's vanilla Ender Chest..."));
        conversions.convert(target).thenAccept(result -> runSync(() -> {
            auditConversion(sender, target, result);
            sendConversionResult(sender, target, result);
        }));
    }

    private void armConvertAll(CommandSender sender) {
        int count = plugin.getServer().getOnlinePlayers().size();
        if (count == 0) {
            sender.sendMessage(TextUtil.component("&eThere are no online players to convert."));
            return;
        }

        long expiresAt = System.currentTimeMillis()
                + plugin.getConfigManager().getConfirmationTimeoutSeconds() * 1000L;
        confirmations.put(senderKey(sender), new PendingConfirmation(PendingAction.CONVERT_ALL, expiresAt));

        sender.sendMessage(TextUtil.component("&6&lConfirmation required"));
        sender.sendMessage(TextUtil.component("&eYou are about to run vanilla Ender Chest conversion for &f"
                + count + "&e online player(s)."));
        sender.sendMessage(TextUtil.component("&eRun &f/ec confirm &ewithin &f"
                + plugin.getConfigManager().getConfirmationTimeoutSeconds() + " seconds&e to continue."));
        sender.sendMessage(TextUtil.component("&7Nothing has been changed yet."));
    }

    private void handleConfirm(CommandSender sender) {
        String key = senderKey(sender);
        PendingConfirmation confirmation = confirmations.remove(key);
        if (confirmation == null || confirmation.expiresAt() < System.currentTimeMillis()) {
            sender.sendMessage(TextUtil.component("&eYou do not have a pending LsEnderChest action to confirm."));
            return;
        }

        switch (confirmation.action()) {
            case CONVERT_ALL -> {
                if (!sender.hasPermission("enderchest.convert")) {
                    sendNoPermission(sender);
                    return;
                }
                executeConvertAll(sender);
            }
        }
    }

    private void executeConvertAll(CommandSender sender) {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) {
            sender.sendMessage(TextUtil.component("&eThere are no online players to convert."));
            return;
        }

        sender.sendMessage(TextUtil.component("&eConfirmed. Starting safe vanilla Ender Chest migration for &f"
                + players.size() + "&e online player(s)..."));

        List<CompletableFuture<ConversionManager.Result>> futures = new ArrayList<>();
        for (Player player : players) {
            futures.add(conversions.convert(player));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) ->
                runSync(() -> {
                    int completed = 0;
                    int skipped = 0;
                    int failed = 0;

                    for (int i = 0; i < futures.size(); i++) {
                        CompletableFuture<ConversionManager.Result> future = futures.get(i);
                        Player convertedPlayer = players.get(i);
                        ConversionManager.Result result;
                        try {
                            result = future.join();
                        } catch (RuntimeException exception) {
                            failed++;
                            auditConversionFailure(sender, convertedPlayer, exception);
                            continue;
                        }

                        auditConversion(sender, convertedPlayer, result);

                        switch (result) {
                            case CONVERTED, EMPTY, OLD_CONVERTER_DUPLICATE_CLEANED, ALREADY_CONVERTED -> completed++;
                            case CUSTOM_CHEST_NOT_EMPTY, BUSY, VANILLA_CHANGED, PLAYER_LEFT -> skipped++;
                            case STORAGE_ERROR -> failed++;
                        }
                    }

                    sender.sendMessage(TextUtil.component("&aConversion pass finished. &f"
                            + completed + " &acomplete, &e" + skipped + " &eskipped, &c" + failed + " &cfailed."));
                }));
    }

    private void auditConversionFailure(CommandSender sender, Player target, Throwable failure) {
        PlayerIdManager.PlayerIdentity identity = chests.resolveIdentity(target.getName());
        if (identity == null) {
            return;
        }
        String details = "Manual vanilla conversion threw " + failure.getClass().getSimpleName();
        if (sender instanceof Player admin) {
            plugin.getLogManager().logAdminAction(
                    identity.uuid(), identity.name(), java.util.UUID.randomUUID(), admin, "CONVERT_FAILED", details
            );
        } else {
            plugin.getLogManager().logSystem(identity.uuid(), java.util.UUID.randomUUID(), "CONVERT_FAILED",
                    details + " actor=" + sender.getName());
        }
    }

    private void auditConversion(CommandSender sender, Player target, ConversionManager.Result result) {
        PlayerIdManager.PlayerIdentity identity = chests.resolveIdentity(target.getName());
        if (identity == null) {
            return;
        }
        String details = "Manual vanilla conversion result=" + result.name();
        if (sender instanceof Player admin) {
            plugin.getLogManager().logAdminAction(
                    identity.uuid(), identity.name(), java.util.UUID.randomUUID(), admin, "CONVERT", details
            );
        } else {
            plugin.getLogManager().logSystem(identity.uuid(), java.util.UUID.randomUUID(), "CONVERT",
                    details + " actor=" + sender.getName());
        }
    }

    private void sendConversionResult(CommandSender sender, Player target, ConversionManager.Result result) {
        String name = target.getName();
        switch (result) {
            case CONVERTED -> sender.sendMessage(TextUtil.component(
                    "&aConverted &f" + name + "&a's vanilla Ender Chest and safely cleared the vanilla copy."));
            case EMPTY -> sender.sendMessage(TextUtil.component(
                    "&7" + name + " had no vanilla Ender Chest items to migrate."));
            case OLD_CONVERTER_DUPLICATE_CLEANED -> sender.sendMessage(TextUtil.component(
                    "&aVerified the old converter copy for &f" + name + "&a and cleared the duplicate vanilla contents."));
            case ALREADY_CONVERTED -> sender.sendMessage(TextUtil.component(
                    "&e" + name + " has already been converted by LsEnderChest 2.x."));
            case CUSTOM_CHEST_NOT_EMPTY -> sender.sendMessage(TextUtil.component(
                    "&cConversion stopped: " + name + "'s LsEnderChest already contains different items. Nothing was changed."));
            case BUSY -> sender.sendMessage(TextUtil.component(
                    "&eConversion skipped because " + name + "'s Ender Chest is currently active. Try again shortly."));
            case VANILLA_CHANGED -> sender.sendMessage(TextUtil.component(
                    "&eConversion was cancelled because " + name + "'s vanilla Ender Chest changed during migration."));
            case PLAYER_LEFT -> sender.sendMessage(TextUtil.component(
                    "&eConversion was cancelled because " + name + " left the server."));
            case STORAGE_ERROR -> sender.sendMessage(TextUtil.component(
                    "&cConversion failed because of a storage error. Check the console."));
        }
    }

    private void addKnownPlayers(List<String> suggestions) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            suggestions.add(player.getName());
        }
        if (plugin.getConfigManager().includeOfflinePlayersInTabComplete()) {
            for (OfflinePlayer offlinePlayer : plugin.getServer().getOfflinePlayers()) {
                String name = offlinePlayer.getName();
                if (name != null && !name.isBlank()) {
                    suggestions.add(name);
                }
            }
        }
    }

    private boolean hasValidConfirmation(CommandSender sender) {
        PendingConfirmation confirmation = confirmations.get(senderKey(sender));
        if (confirmation == null) {
            return false;
        }
        if (confirmation.expiresAt() < System.currentTimeMillis()) {
            confirmations.remove(senderKey(sender));
            return false;
        }
        return true;
    }

    private String senderKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return "player:" + player.getUniqueId();
        }
        return "sender:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(TextUtil.component(plugin.getConfigManager().getMessage(
                "invalid-usage",
                "&cUse /lsec, /ec or /enderchest [player|reload|restore <player>|convert <player|all>|confirm].")));
    }

    private void sendNoPermission(CommandSender sender) {
        sender.sendMessage(TextUtil.component(plugin.getConfigManager().getMessage(
                "no-permission", "&cYou do not have permission.")));
    }

    private void runSync(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    private static List<String> filterPrefix(Collection<String> suggestions, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private enum PendingAction {
        CONVERT_ALL
    }

    private record PendingConfirmation(PendingAction action, long expiresAt) {
    }
}
