package com.lopikss.lsenderchest.update;

import com.lopikss.lsenderchest.LsEnderChestPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    private static final String VERSION_URL = "https://lsplugins.com/UpdateNotefier/LsEnderChest.txt";
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/lsenderchest";
    private static final Duration CACHE_TIME = Duration.ofMinutes(30);
    private static final Pattern VERSION_LINE = Pattern.compile(
            "^[vV]?(\\d+(?:\\.\\d+){1,3})(?:[-+][0-9A-Za-z.-]+)?$"
    );
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final LsEnderChestPlugin plugin;
    private final HttpClient client;

    private Instant lastChecked = Instant.EPOCH;
    private Optional<UpdateInfo> cached = Optional.empty();
    private CompletableFuture<Optional<UpdateInfo>> inFlight;

    public UpdateChecker(LsEnderChestPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized CompletableFuture<Optional<UpdateInfo>> checkNow(boolean force) {
        if (!plugin.getConfigManager().isUpdateCheckEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (!force && Duration.between(lastChecked, Instant.now()).compareTo(CACHE_TIME) < 0) {
            return CompletableFuture.completedFuture(cached);
        }

        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }

        String currentVersion = plugin.getPluginMeta().getVersion();

        // The cache-busting query keeps static/CDN caches from serving an old version file.
        URI uri = URI.create(VERSION_URL + "?_=" + System.currentTimeMillis());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "text/plain")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", "lopikss/LsEnderChest/" + currentVersion + " (https://lsplugins.com)")
                .GET()
                .build();

        inFlight = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Update server returned HTTP " + response.statusCode());
                    }
                    return parseLatest(response.body(), currentVersion);
                })
                .whenComplete((result, throwable) -> {
                    synchronized (this) {
                        if (throwable == null) {
                            lastChecked = Instant.now();
                            cached = result;
                        }
                        inFlight = null;
                    }

                    if (throwable != null) {
                        plugin.getLogger().warning("Could not check for updates: " + rootMessage(throwable));
                    } else if (result.isPresent()) {
                        UpdateInfo info = result.get();
                        plugin.getLogger().info("New version available: " + info.latestVersion()
                                + " (current: " + info.currentVersion() + "). " + DOWNLOAD_URL);
                    } else if (force) {
                        plugin.getLogger().info("Update check complete. Installed version "
                                + currentVersion + " is up to date.");
                    }
                });

        return inFlight;
    }

    public void notifyPlayer(Player player, UpdateInfo info) {
        if (!plugin.getConfigManager().shouldNotifyAdminsAboutUpdates()) {
            return;
        }
        if (!player.isOp() && !player.hasPermission("enderchest.admin")) {
            return;
        }

        player.sendMessage(Component.text("━━━━━━━━━━ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("LsEnderChest", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" ━━━━━━━━━━", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.text("Update available", NamedTextColor.YELLOW, TextDecoration.BOLD));
        player.sendMessage(Component.text("You're using ", NamedTextColor.GRAY)
                .append(Component.text(info.currentVersion(), NamedTextColor.RED))
                .append(Component.text("  →  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(info.latestVersion(), NamedTextColor.GREEN)));
        player.sendMessage(Component.text("[Download " + info.latestVersion() + " on Modrinth]", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(DOWNLOAD_URL))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open the LsEnderChest Modrinth page", NamedTextColor.GRAY))));
        player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
    }

    private Optional<UpdateInfo> parseLatest(String body, String currentVersion) {
        String firstLine = body == null
                ? ""
                : body.replace("\uFEFF", "").lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .findFirst()
                        .orElse("");

        Matcher matcher = VERSION_LINE.matcher(firstLine);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version returned by update server: '" + firstLine + "'");
        }

        String latestVersion = matcher.group(1);
        if (compareVersions(latestVersion, currentVersion) > 0) {
            return Optional.of(new UpdateInfo(latestVersion, currentVersion));
        }
        return Optional.empty();
    }

    private static int compareVersions(String left, String right) {
        int[] a = numericParts(left);
        int[] b = numericParts(right);
        int length = Math.max(a.length, b.length);

        for (int i = 0; i < length; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        Matcher matcher = NUMBER.matcher(version);
        int[] temporary = new int[8];
        int count = 0;

        while (matcher.find() && count < temporary.length) {
            try {
                temporary[count++] = Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
                temporary[count++] = 0;
            }
        }

        int[] result = new int[count];
        System.arraycopy(temporary, 0, result, 0, count);
        return result;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record UpdateInfo(String latestVersion, String currentVersion) {
    }
}
