package dev.mobzone.skins;

import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Optional add-on: gives MythicMobs zombies and bosses a player skin from the website (/admin/skins).
 * Each skin PNG is turned into a signed skin ONCE through MineSkin (needs a MineSkin API key in config.yml),
 * cached in skins-cache.yml, and then handed to LibsDisguises. Mobs with no ready skin look normal.
 * LibsDisguises is called through reflection so this add-on compiles without it.
 */
public final class SkinsPlugin extends JavaPlugin implements Listener {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Map<String, String> urls = Map.of();          // mob (lowercase) -> PNG url
    private volatile Map<String, String[]> ready = Map.of();       // mob (lowercase) -> {value, signature}
    private volatile String status = "not fetched yet";
    private final Object lock = new Object();
    private int task = -1;
    private File cacheFile;
    private YamlConfiguration cache = new YamlConfiguration();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cacheFile = new File(getDataFolder(), "skins-cache.yml");
        cache = YamlConfiguration.loadConfiguration(cacheFile);
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

    private static String key(String url) { return UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString(); }

    private void fetch() {
        synchronized (lock) {
            String url = getConfig().getString("url", "");
            if (url == null || url.isBlank()) { status = "off (no url set)"; return; }
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
                String k = getConfig().getString("key", "");
                if (k != null && !k.isBlank()) rb.header("Authorization", "Bearer " + k);
                HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) { status = "failed: HTTP " + r.statusCode() + " (is the key right?)"; return; }
                ConfigurationSection sec = YamlConfiguration.loadConfiguration(new java.io.StringReader(r.body())).getConfigurationSection("skins");
                Map<String, String> m = new HashMap<>();
                if (sec != null) for (String n : sec.getKeys(false)) {
                    String u = sec.getString(n);
                    if (u != null && u.startsWith("http")) m.put(n.toLowerCase(Locale.ROOT), u);
                }
                urls = Map.copyOf(m);
            } catch (Exception ex) {
                status = "failed to download the list: " + ex.getMessage();
                return;
            }
            resolveAll();
        }
    }

    /** Turns every listed PNG into a signed skin (once; results are cached by URL). */
    private void resolveAll() {
        String mineKey = getConfig().getString("mineskin-key", "");
        Map<String, String[]> out = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (var e : urls.entrySet()) {
            String ck = "skins." + key(e.getValue());
            String v = cache.getString(ck + ".value"), s = cache.getString(ck + ".signature");
            if (v == null || s == null) {
                if (mineKey == null || mineKey.isBlank()) { problems.add(e.getKey() + ": no mineskin-key in config.yml"); continue; }
                String[] got = mineskin(e.getValue(), mineKey, e.getKey());
                if (got == null) { problems.add(e.getKey() + ": MineSkin failed (see console)"); continue; }
                v = got[0]; s = got[1];
                cache.set(ck + ".value", v);
                cache.set(ck + ".signature", s);
                cache.set(ck + ".url", e.getValue());
                try { cache.save(cacheFile); } catch (Exception ignored) { }
                try { Thread.sleep(6000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            out.put(e.getKey(), new String[]{v, s});
        }
        ready = Map.copyOf(out);
        status = out.size() + " skin(s) ready" + (problems.isEmpty() ? "" : "; problems: " + String.join(", ", problems));
    }

    private String[] mineskin(String png, String apiKey, String label) {
        try {
            String body = "{\"url\":\"" + png.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"name\":\"deadcell-" + label + "\",\"visibility\":\"unlisted\"}";
            HttpRequest rq = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/generate")).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").header("User-Agent", "MobZoneSkins/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
            String[] t = find(r.body());
            if (t == null) {
                String job = jobId(r.body());
                for (int i = 0; job != null && i < 20 && t == null; i++) {
                    Thread.sleep(3000);
                    HttpRequest q = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/queue/" + job)).timeout(Duration.ofSeconds(20))
                            .header("Authorization", "Bearer " + apiKey).header("User-Agent", "MobZoneSkins/1.0").GET().build();
                    r = http.send(q, HttpResponse.BodyHandlers.ofString());
                    t = find(r.body());
                }
            }
            if (t == null) getLogger().warning("MineSkin gave no skin for " + label + " (HTTP " + r.statusCode() + "): " + r.body().substring(0, Math.min(300, r.body().length())));
            else getLogger().info("Made the " + label + " skin with MineSkin.");
            return t;
        } catch (Exception ex) {
            getLogger().warning("MineSkin request for " + label + " failed: " + ex);
            return null;
        }
    }

    private static String jobId(String json) {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new java.io.StringReader(json));
            String id = y.getString("job.id");
            return id != null ? id : y.getString("id");
        } catch (Exception e) { return null; }
    }

    /** Finds {value, signature} anywhere in a MineSkin JSON answer. */
    private static String[] find(String json) {
        try {
            return walk(YamlConfiguration.loadConfiguration(new java.io.StringReader(json)));
        } catch (Exception e) { return null; }
    }

    private static String[] walk(ConfigurationSection s) {
        if (s.isString("value") && s.isString("signature") && s.getString("value").length() > 50) return new String[]{s.getString("value"), s.getString("signature")};
        for (String k : s.getKeys(false)) {
            if (s.isConfigurationSection(k)) { String[] r = walk(s.getConfigurationSection(k)); if (r != null) return r; }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(MythicMobSpawnEvent e) {
        String name = e.getMobType().getInternalName();
        String[] tex = ready.get(name.toLowerCase(Locale.ROOT));
        if (tex == null) return;
        Entity ent = e.getEntity();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!ent.isValid()) return;
            try {
                disguise(ent, tex, name);
            } catch (Throwable t) {
                getLogger().warning("Could not apply the " + name + " skin: " + t);
            }
        });
    }

    private boolean logged;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void disguise(Entity ent, String[] tex, String name) throws Exception {
        Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
        Class<?> pd = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
        Class<?> base = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
        if (Boolean.TRUE.equals(api.getMethod("isDisguised", Entity.class).invoke(null, ent))) return;
        String shown = ent.getCustomName();
        boolean visible = ent.isCustomNameVisible();
        Object d = pd.getConstructor(String.class).newInstance(shown == null ? name : org.bukkit.ChatColor.stripColor(shown));
        // 1) a PacketEvents profile with the signed skin, 2) raw JSON as a fallback
        boolean done = false;
        try {
            Class<?> up = Class.forName("com.github.retrooper.packetevents.protocol.player.UserProfile");
            Class<?> tp = Class.forName("com.github.retrooper.packetevents.protocol.player.TextureProperty");
            Object prop = tp.getConstructor(String.class, String.class, String.class).newInstance("textures", tex[0], tex[1]);
            List<Object> props = new ArrayList<>();
            props.add(prop);
            Object prof = up.getConstructor(UUID.class, String.class, List.class).newInstance(UUID.nameUUIDFromBytes(("mz" + name).getBytes(StandardCharsets.UTF_8)), "Zombie", props);
            for (var m : pd.getMethods()) {
                if (m.getName().equals("setSkin") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(up)) { m.invoke(d, prof); done = true; break; }
            }
        } catch (Throwable t) {
            if (!logged) getLogger().info("Profile skin path not available: " + t);
        }
        if (!done) {
            String json = "{\"id\":\"" + UUID.nameUUIDFromBytes(("mz" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "") + "\",\"name\":\"Zombie\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + tex[0] + "\",\"signature\":\"" + tex[1] + "\"}]}";
            pd.getMethod("setSkin", String.class).invoke(d, json);
        }
        if (!logged) { logged = true; getLogger().info("Applied the " + name + " skin using the " + (done ? "profile" : "JSON") + " method."); }
        try {   // keep the mob's own name above its head
            Object w = base.getMethod("getWatcher").invoke(d);
            if (shown != null) w.getClass().getMethod("setCustomName", String.class).invoke(w, shown);
            w.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(w, visible);
        } catch (Throwable ignored) { }
        api.getMethod("disguiseToAll", Entity.class, base).invoke(null, ent, d);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        reloadConfig();
        start();
        s.sendMessage("Zombie skins: working on it (making a skin can take a few seconds each)...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            fetch();
            Bukkit.getScheduler().runTask(this, () -> s.sendMessage("Zombie skins: " + status + ". New spawns use them; zombies already alive keep their old look."));
        });
        return true;
    }
}
