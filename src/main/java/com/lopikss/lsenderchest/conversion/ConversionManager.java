package com.lopikss.lsenderchest.conversion;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import com.lopikss.lsenderchest.manager.ChestManager;
import com.lopikss.lsenderchest.util.ItemStackUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.CompletableFuture;

public final class ConversionManager {

    public enum Result {
        CONVERTED,
        EMPTY,
        OLD_CONVERTER_DUPLICATE_CLEANED,
        ALREADY_CONVERTED,
        CUSTOM_CHEST_NOT_EMPTY,
        BUSY,
        VANILLA_CHANGED,
        PLAYER_LEFT,
        STORAGE_ERROR
    }

    private final LsEnderChestPlugin plugin;
    private final ChestManager chestManager;
    private final ConvertedPlayersStore convertedPlayers;

    public ConversionManager(LsEnderChestPlugin plugin,
                             ChestManager chestManager,
                             ConvertedPlayersStore convertedPlayers) {
        this.plugin = plugin;
        this.chestManager = chestManager;
        this.convertedPlayers = convertedPlayers;
    }

    public CompletableFuture<Result> convert(Player player) {
        if (convertedPlayers.isConverted(player.getUniqueId())) {
            return CompletableFuture.completedFuture(Result.ALREADY_CONVERTED);
        }

        ItemStack[] vanillaSnapshot = ItemStackUtil.cloneArray(player.getEnderChest().getContents(), 27);
        if (ItemStackUtil.isEmpty(vanillaSnapshot)) {
            convertedPlayers.markConverted(player.getUniqueId());
            return CompletableFuture.completedFuture(Result.EMPTY);
        }

        CompletableFuture<Result> result = new CompletableFuture<>();
        chestManager.importVanillaChest(player, vanillaSnapshot).whenComplete((importResult, throwable) -> {
            Runnable complete = () -> {
                if (throwable != null) {
                    plugin.getLogger().severe("Unexpected conversion failure for " + player.getName() + ": "
                            + rootMessage(throwable));
                    result.complete(Result.STORAGE_ERROR);
                    return;
                }

                Result mapped = switch (importResult) {
                    case CONVERTED -> Result.CONVERTED;
                    case ALREADY_COPIED_CLEANED -> Result.OLD_CONVERTER_DUPLICATE_CLEANED;
                    case CUSTOM_CHEST_NOT_EMPTY -> Result.CUSTOM_CHEST_NOT_EMPTY;
                    case BUSY -> Result.BUSY;
                    case VANILLA_CHANGED -> Result.VANILLA_CHANGED;
                    case PLAYER_LEFT -> Result.PLAYER_LEFT;
                    case STORAGE_ERROR -> Result.STORAGE_ERROR;
                };

                if (mapped == Result.CONVERTED || mapped == Result.OLD_CONVERTER_DUPLICATE_CLEANED) {
                    convertedPlayers.markConverted(player.getUniqueId());
                }
                result.complete(mapped);
            };

            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, complete);
            } else {
                result.complete(Result.STORAGE_ERROR);
            }
        });
        return result;
    }

    public boolean isConverted(Player player) {
        return convertedPlayers.isConverted(player.getUniqueId());
    }

    public ConvertedPlayersStore getConvertedPlayers() {
        return convertedPlayers;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
