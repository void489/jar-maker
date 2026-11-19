package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Downloads sell worth and shop prices from the website (voidlabs.fun/admin/prices) so they can be edited there.
 * Config (economy section):
 *   prices-url: "https://voidlabs.fun/prices.json"     (empty = off, local worth.yml and shop.yml are used)
 *   prices-key: "..."                                   (sent as "Authorization: Bearer ...", must match PRICES_KEY on the site)
 *   prices-refresh-minutes: 5
 * The last good download is kept in prices-cache.json and used if the site is down at startup.
 * A bad or unreachable download never changes the prices that are already active.
 */
public final class PriceSync {
    private final MobZonePlugin plugin;
    private final Market market;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private BukkitTask task;
    private volatile String status = "off";

    public PriceSync(MobZonePlugin plugin, Market market) { this.plugin = plugin; this.market = market; }

    public String status() { return status; }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /** Called after the local files were (re)loaded. Applies the cached download, then fetches a fresh one. */
    public void reload() {
        stop();
        String url = plugin.getConfig().getString("economy.prices-url", "");
        if (url == null || url.isBlank()) { status = "off (no economy.prices-url set)"; return; }
        File cache = new File(plugin.getDataFolder(), "prices-cache.json");
        if (cache.isFile()) {
            try { apply(Files.readString(cache.toPath(), StandardCharsets.UTF_8), false, "cached copy"); }
            catch (Exception ex) { plugin.getLogger().warning("prices-cache.json could not be read: " + ex.getMessage()); }
        }
        int minutes = Math.max(1, plugin.getConfig().getInt("economy.prices-refresh-minutes", 5));
        String key = plugin.getConfig().getString("economy.prices-key", "");
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> fetch(url.trim(), key, null), 40L, minutes * 1200L);
    }

    /** /mobzone prices: fetch right now and report back on the main thread. */
    public void fetchNow(Consumer<String> report) {
        String url = plugin.getConfig().getString("economy.prices-url", "");
        if (url == null || url.isBlank()) { report.accept("economy.prices-url is empty in config.yml, so prices come from worth.yml and shop.yml."); return; }
        String key = plugin.getConfig().getString("economy.prices-key", "");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fetch(url.trim(), key, report));
    }

    /** Runs off the main thread. */
    private void fetch(String url, String key, Consumer<String> report) {
        String result;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "MobZone-Prices");
            if (key != null && !key.isBlank()) rb.header("Authorization", "Bearer " + key.trim());
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                result = "website answered " + r.statusCode() + (r.statusCode() == 403 ? " (check economy.prices-key matches PRICES_KEY on the site)" : "");
            } else if (r.body().length() > 2_000_000) {
                result = "prices file is too big";
            } else {
                String body = r.body();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String err = apply(body, true, "website");
                    if (report != null) report.accept(err == null ? "Prices updated from the website." : "Prices NOT updated: " + err);
                });
                return;
            }
        } catch (Exception ex) {
            result = "could not reach the website (" + ex.getClass().getSimpleName() + ")";
        }
        status = "error: " + result;
        plugin.getLogger().warning("Prices not updated, keeping the current ones: " + result);
        if (report != null) { String msg = "Prices NOT updated: " + result; Bukkit.getScheduler().runTask(plugin, () -> report.accept(msg)); }
    }

    /** Main thread. Returns an error message or null. */
    private String apply(String body, boolean saveCache, String from) {
        var y = new YamlConfiguration();
        try { y.loadFromString(body); }
        catch (InvalidConfigurationException ex) { status = "error: bad file"; plugin.getLogger().warning("Prices from " + from + " are not valid, keeping the current ones."); return "the file is not valid JSON"; }
        String err = market.applyRemote(y);
        if (err != null) { status = "error: " + err; return err; }
        status = "ok (" + from + ", " + java.time.LocalTime.now().withNano(0) + ")";
        if (saveCache) {
            try { Files.writeString(new File(plugin.getDataFolder(), "prices-cache.json").toPath(), body, StandardCharsets.UTF_8); }
            catch (Exception ex) { plugin.getLogger().warning("Couldn't write prices-cache.json: " + ex.getMessage()); }
        }
        return null;
    }
}
