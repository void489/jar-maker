package dev.mobzone.skins;

import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Optional add-on: gives MythicMobs zombies and bosses a player skin from the website (/admin/skins).
 * Needs LibsDisguises. The mob stays a real zombie (same hitbox, health and drops); only its look changes.
 * Delete MobZoneSkins.jar to turn it off. Mobs with no uploaded skin look normal.
 */
public final class SkinsPlugin extends JavaPlugin implements Listener {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Map<String, String> skins = Map.of();   // internal mob name (lowercase) -> PNG url
    private volatile String status = "not fetched yet";
    private int task = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        start();
        getLogger().info("Zombie skins on.");
    }

    @Override
    public void onDisable() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
    }

    private void start() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        long ticks = Math.max(1, getConfig().getInt("refresh-minutes", 5)) * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::fetch, 20L, ticks).getTaskId();
    }

    private void fetch() {
        String url = getConfig().getString("url", "");
        if (url == null || url.isBlank()) { status = "off (no url set)"; return; }
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
            String key = getConfig().getString("key", "");
            if (key != null && !key.isBlank()) rb.header("Authorization", "Bearer " + key);
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { status = "failed: HTTP " + r.statusCode() + " (is the key right?)"; return; }
            var sec = YamlConfiguration.loadConfiguration(new java.io.StringReader(r.body())).getConfigurationSection("skins");
            Map<String, String> m = new HashMap<>();
            if (sec != null) for (String k : sec.getKeys(false)) {
                String u = sec.getString(k);
                if (u != null && u.startsWith("http")) m.put(k.toLowerCase(java.util.Locale.ROOT), u);
            }
            skins = Map.copyOf(m);
            status = "ok, " + m.size() + " skins";
        } catch (Exception ex) {
            status = "failed: " + ex.getMessage();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(MythicMobSpawnEvent e) {
        String name = e.getMobType().getInternalName();
        String url = skins.get(name.toLowerCase(java.util.Locale.ROOT));
        if (url == null) return;
        Entity ent = e.getEntity();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!ent.isValid() || DisguiseAPI.isDisguised(ent)) return;
            try {
                PlayerDisguise d = new PlayerDisguise("Zombie");
                d.setSkin(url);
                DisguiseAPI.disguiseToAll(ent, d);
            } catch (Throwable t) {
                getLogger().warning("Could not apply the " + name + " skin: " + t);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        reloadConfig();
        start();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            fetch();
            Bukkit.getScheduler().runTask(this, () -> s.sendMessage("Zombie skins: " + status + ". New spawns use them; zombies already alive keep their old look."));
        });
        return true;
    }
}
