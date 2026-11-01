package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Timed server events (double cash, Bio Rush...). One at a time, on a timer, announced in chat. */
public class EventManager {
    private record Def(String id, String name, double cash, double tokens) {}

    private final MobZonePlugin plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private BukkitTask task;
    private boolean enabled;
    private long intervalMs, durationMs, minPlayers;
    private Def active;
    private long endsAt, nextAt;

    public EventManager(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        if (task != null) task.cancel();
        defs.clear();
        var c = plugin.getConfig();
        enabled = c.getBoolean("events.enabled", true);
        intervalMs = Math.max(1, c.getLong("events.interval-minutes", 180)) * 60_000L;
        durationMs = Math.max(1, c.getLong("events.duration-minutes", 15)) * 60_000L;
        minPlayers = c.getLong("events.min-players", 1);
        var s = c.getConfigurationSection("events.types");
        if (s != null) for (String id : s.getKeys(false)) {
            var t = s.getConfigurationSection(id);
            if (t == null) continue;
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), t.getString("name", id),
                    t.getDouble("cash", 1.0), t.getDouble("tokens", 1.0)));
        }
        if (nextAt == 0) nextAt = System.currentTimeMillis() + intervalMs;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L);
    }

    public void shutdown() { if (task != null) task.cancel(); }

    private void tick() {
        long now = System.currentTimeMillis();
        if (active != null) {
            if (now >= endsAt) stop(true);
            return;
        }
        if (!enabled || defs.isEmpty() || now < nextAt) return;
        if (Bukkit.getOnlinePlayers().size() < minPlayers) { nextAt = now + 60_000L; return; }
        List<Def> all = new ArrayList<>(defs.values());
        start(all.get(ThreadLocalRandom.current().nextInt(all.size())), durationMs);
    }

    public boolean start(String id, long minutes) {
        Def d = defs.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        start(d, minutes * 60_000L);
        return true;
    }

    private void start(Def d, long ms) {
        active = d;
        endsAt = System.currentTimeMillis() + ms;
        Util.broadcast("&#A855F7&l[Event] " + d.name() + " &7has started for &f" + (ms / 60_000L) + " minutes&7! "
                + describe(d));
    }

    public void stop(boolean announce) {
        if (active == null) return;
        if (announce) Util.broadcast("&#A855F7&l[Event] " + active.name() + " &7has ended.");
        active = null;
        nextAt = System.currentTimeMillis() + intervalMs;
    }

    private static String describe(Def d) {
        List<String> p = new ArrayList<>();
        if (d.cash() != 1.0) p.add("&a" + d.cash() + "x cash from zombies");
        if (d.tokens() != 1.0) p.add("&b" + d.tokens() + "x Bio Tokens from bosses");
        return String.join("&7, ", p);
    }

    public double cashMultiplier() { return active == null ? 1.0 : active.cash(); }

    public double tokenMultiplier() { return active == null ? 1.0 : active.tokens(); }

    public java.util.Set<String> ids() { return defs.keySet(); }

    public String status() {
        long now = System.currentTimeMillis();
        if (active != null)
            return "&#A855F7[Event] " + active.name() + " &7is live! " + describe(active) + " &8(" + Math.max(1, (endsAt - now) / 60_000L) + " min left)";
        if (!enabled) return "&#A855F7[Event] &7Events are off.";
        return "&#A855F7[Event] &7No event right now. Next one in about &f" + Math.max(1, (nextAt - now) / 60_000L) + " minutes&7.";
    }
}
