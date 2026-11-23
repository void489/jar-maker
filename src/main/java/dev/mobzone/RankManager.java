package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

/**
 * Member ranks. Ranks and their limits are made on the website (Ranks tab) and downloaded from /ranks.json.
 * Each player holds one rank (default "member"). Limits: AH listings, max items per order, homes, Bio Tokens per minute.
 * Non-default ranks also get a LuckPerms group mem_<id> with a coloured chat/tab prefix.
 */
public final class RankManager implements Listener, CommandExecutor, TabCompleter {
    public record Rank(String id, String name, String color, int ah, int orders, int homes, int tokens) {}

    private final MobZonePlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Map<String, Rank> ranks = new LinkedHashMap<>();
    private final Map<UUID, String> holders = new HashMap<>();
    private BukkitTask fetchTask, payTask;
    private volatile String status = "not loaded";

    public RankManager(MobZonePlugin plugin) { this.plugin = plugin; ranks = defaults(); }

    private Map<String, Rank> defaults() {
        Map<String, Rank> m = new LinkedHashMap<>();
        m.put("member", new Rank("member", "Member", "&7", 10, 2304, 1, 1));
        return m;
    }

    // ------------------------------------------------------------ lifecycle

    public void reload() {
        shutdown();
        loadHolders();
        File cache = new File(plugin.getDataFolder(), "ranks-cache.json");
        if (cache.isFile()) {
            try { apply(Files.readString(cache.toPath(), StandardCharsets.UTF_8), false); } catch (Exception ignored) {}
        }
        String url = url();
        if (!url.isBlank()) {
            fetchTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> fetch(null), 60L, 5 * 1200L);
        } else status = "off (set economy.prices-url)";
        if (plugin.getConfig().getBoolean("ranks.pay-tokens", true))
            payTask = Bukkit.getScheduler().runTaskTimer(plugin, this::payTokens, 1200L, 1200L);
    }

    public void shutdown() {
        if (fetchTask != null) { fetchTask.cancel(); fetchTask = null; }
        if (payTask != null) { payTask.cancel(); payTask = null; }
    }

    public String status() { return status; }

    private String url() {
        String u = plugin.getConfig().getString("ranks.url", "");
        if (u != null && !u.isBlank()) return u.trim();
        String p = plugin.getConfig().getString("economy.prices-url", "");
        if (p == null || p.isBlank()) return "";
        return p.trim().replace("prices.json", "ranks.json");
    }

    public void fetchNow(java.util.function.Consumer<String> report) {
        if (url().isBlank()) { report.accept("Set economy.prices-url in config.yml first."); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fetch(report));
    }

    private void fetch(java.util.function.Consumer<String> report) {
        String key = plugin.getConfig().getString("economy.prices-key", "");
        String result;
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url())).timeout(Duration.ofSeconds(10)).header("User-Agent", "MobZone-Ranks");
            if (key != null && !key.isBlank()) rb.header("Authorization", "Bearer " + key.trim());
            HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200 && r.body().length() < 500_000) {
                String body = r.body();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String err = apply(body, true);
                    if (report != null) report.accept(err == null ? "Ranks updated from the website (" + ranks.size() + ")." : "Ranks NOT updated: " + err);
                });
                return;
            }
            result = "website answered " + r.statusCode() + (r.statusCode() == 404 ? " (redeploy the new main.py)" : r.statusCode() == 403 ? " (check economy.prices-key)" : "");
        } catch (Exception ex) { result = "could not reach the website (" + ex.getClass().getSimpleName() + ")"; }
        status = "error: " + result;
        String msg = "Ranks NOT updated: " + result;
        if (report != null) Bukkit.getScheduler().runTask(plugin, () -> report.accept(msg));
    }

    /** Main thread. */
    private String apply(String body, boolean saveCache) {
        var y = new YamlConfiguration();
        try { y.loadFromString(body); } catch (Exception ex) { return "bad file"; }
        ConfigurationSection s = y.getConfigurationSection("ranks");
        if (s == null) return "no ranks in file";
        Map<String, Rank> m = new LinkedHashMap<>();
        m.put("member", defaults().get("member"));
        for (String id : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(id);
            if (r == null) continue;
            m.put(id, new Rank(id, r.getString("name", id), r.getString("color", "&7"),
                    r.getInt("ah", 10), r.getInt("orders", 2304), r.getInt("homes", 1), r.getInt("tokens", 1)));
        }
        ranks = m;
        status = "ok (" + m.size() + " ranks, " + java.time.LocalTime.now().withNano(0) + ")";
        if (saveCache) {
            try { Files.writeString(new File(plugin.getDataFolder(), "ranks-cache.json").toPath(), body, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        if (plugin.getConfig().getBoolean("ranks.luckperms", true)) syncGroups();
        return null;
    }

    private void syncGroups() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return;
        for (Rank r : ranks.values()) {
            if (r.id().equals("member")) continue;
            Util.console("lp creategroup mem_" + r.id());
            Util.console("lp group mem_" + r.id() + " meta setprefix 100 \"" + r.color() + "[" + r.name() + "] \"");
        }
    }

    // ------------------------------------------------------------ holders

    private File holdersFile() { return new File(plugin.getDataFolder(), "member-ranks.yml"); }

    private void loadHolders() {
        holders.clear();
        var y = YamlConfiguration.loadConfiguration(holdersFile());
        for (String k : y.getKeys(false)) {
            try { holders.put(UUID.fromString(k), y.getString(k, "member")); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveHolders() {
        var y = new YamlConfiguration();
        holders.forEach((u, r) -> { if (!r.equals("member")) y.set(u.toString(), r); });
        try { y.save(holdersFile()); } catch (Exception ex) { plugin.getLogger().warning("Couldn't save member-ranks.yml: " + ex.getMessage()); }
    }

    public Rank rankOf(UUID id) {
        Rank r = ranks.get(holders.getOrDefault(id, "member"));
        return r != null ? r : ranks.get("member");
    }

    public Rank rankOf(Player p) { return rankOf(p.getUniqueId()); }

    private void give(CommandSender by, OfflinePlayer t, Rank r) {
        String old = holders.getOrDefault(t.getUniqueId(), "member");
        holders.put(t.getUniqueId(), r.id());
        saveHolders();
        String name = t.getName() == null ? t.getUniqueId().toString() : t.getName();
        if (plugin.getConfig().getBoolean("ranks.luckperms", true) && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            if (!old.equals("member")) Util.console("lp user " + name + " parent remove mem_" + old);
            if (!r.id().equals("member")) Util.console("lp user " + name + " parent add mem_" + r.id());
        }
        Util.tell(by, "&#A3E635[Rank] &f" + name + " &7is now " + r.color() + r.name() + "&7.");
        Player on = t.getPlayer();
        if (on != null) Util.tell(on, "&#A3E635[Rank] &7You are now " + r.color() + r.name() + "&7! Limits: &f" + r.ah() + " &7AH, &f" + r.homes() + " &7homes, &f" + r.tokens() + " &7tokens/min.");
    }

    // ------------------------------------------------------------ limits in use

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().trim();
        if (raw.startsWith("/")) raw = raw.substring(1);
        String[] p = raw.split("\\s+");
        if (p.length == 0) return;
        String c = p[0].toLowerCase(Locale.ROOT);
        if (c.startsWith("essentials:")) c = c.substring(11);
        if (!(c.equals("sethome") || c.equals("esethome") || c.equals("createhome") || c.equals("ecreatehome"))) return;
        Player pl = e.getPlayer();
        if (pl.hasPermission("mobzone.admin")) return;
        if (Market.modeOf(pl.getWorld().getName()) == null) return;
        String name = p.length > 1 ? p[1].toLowerCase(Locale.ROOT) : "home";
        Rank r = rankOf(pl);
        List<String> homes = homesOf(pl);
        if (homes == null) return;
        boolean exists = false;
        for (String h : homes) if (h.equalsIgnoreCase(name)) exists = true;
        if (exists) return;
        if (homes.size() >= r.homes()) {
            e.setCancelled(true);
            Util.tell(pl, "&#A3E635[Homes] &cYou can only have &f" + r.homes() + " &chome" + (r.homes() == 1 ? "" : "s") + " as " + r.color() + r.name()
                    + "&c. Delete one with &f/delhome <name>&c.");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> homesOf(Player p) {
        try {
            var ess = Bukkit.getPluginManager().getPlugin("Essentials");
            if (ess == null) return null;
            Method gu = ess.getClass().getMethod("getUser", Player.class);
            Object u = gu.invoke(ess, p);
            Method gh = u.getClass().getMethod("getHomes");
            return new ArrayList<>((Collection<String>) gh.invoke(u));
        } catch (Exception ex) { return null; }
    }

    private boolean afk(Player p) {
        try {
            var ess = Bukkit.getPluginManager().getPlugin("Essentials");
            if (ess == null) return false;
            Object u = ess.getClass().getMethod("getUser", Player.class).invoke(ess, p);
            return (boolean) u.getClass().getMethod("isAfk").invoke(u);
        } catch (Exception ex) { return false; }
    }

    private void payTokens() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String w = p.getWorld().getName().toLowerCase(Locale.ROOT);
            boolean ok = w.equals("prison") || w.startsWith("oneblock") || w.startsWith("smp");
            if (!ok || afk(p)) continue;
            int n = rankOf(p).tokens();
            if (n > 0) Util.console("points give " + p.getName() + " " + n);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { /* keeps ranks.get warm; nothing to do */ }

    // ------------------------------------------------------------ /rank

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("info")) {
            if (s instanceof Player p) {
                Rank r = rankOf(p);
                Util.tell(s, "&#A3E635[Rank] &7You are " + r.color() + r.name() + "&7: &f" + r.ah() + " &7AH listings, &f" + Util.money(r.orders())
                        + " &7items per order, &f" + r.homes() + " &7homes, &f" + r.tokens() + " &7Bio Tokens per minute.");
            } else Util.tell(s, "&7Use /rank list or /rank give <player> <rank>.");
            return true;
        }
        if (a[0].equalsIgnoreCase("list")) {
            StringBuilder sb = new StringBuilder("&#A3E635[Ranks] ");
            for (Rank r : ranks.values()) sb.append(r.color()).append(r.name()).append(" &8(").append(r.id()).append(") ");
            Util.tell(s, sb.toString());
            return true;
        }
        if (!s.hasPermission("mobzone.admin")) { Util.tell(s, "&cNo permission."); return true; }
        if (a[0].equalsIgnoreCase("refresh")) {
            Util.tell(s, "&7Checking the website...");
            fetchNow(m -> Util.tell(s, m.startsWith("Ranks updated") ? "&a" + m : "&c" + m));
            return true;
        }
        if (a[0].equalsIgnoreCase("give") && a.length >= 3) {
            Rank r = ranks.get(a[2].toLowerCase(Locale.ROOT));
            if (r == null) { Util.tell(s, "&cNo such rank. Use &f/rank list&c."); return true; }
            OfflinePlayer t = Bukkit.getPlayerExact(a[1]);
            if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (t == null) { Util.tell(s, "&cThat player has never joined."); return true; }
            give(s, t, r);
            return true;
        }
        Util.tell(s, "&7Use &f/rank give <player> <rank>&7, &f/rank list&7, &f/rank refresh&7.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { out.addAll(List.of("list", "info")); if (s.hasPermission("mobzone.admin")) out.addAll(List.of("give", "refresh")); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("give") && s.hasPermission("mobzone.admin")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("give") && s.hasPermission("mobzone.admin")) out.addAll(ranks.keySet());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
