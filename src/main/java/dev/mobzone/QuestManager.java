package dev.mobzone;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Three random daily quests per player. Progress counts zombie kills and boss fights. */
public class QuestManager implements Listener {
    private record Def(String id, String desc, String type, String mob, int target, long cash, long tokens, int minKills) {}

    private static final class Daily {
        long day;
        final List<String> ids = new ArrayList<>();
        final List<Integer> progress = new ArrayList<>();
        final List<Boolean> done = new ArrayList<>();
    }

    private final MobZonePlugin plugin;
    private final Map<String, Def> pool = new HashMap<>();
    private final List<String> poolOrder = new ArrayList<>();
    private final Map<UUID, Daily> data = new HashMap<>();
    private int perDay = 3;
    private boolean enabled = true;
    private BukkitTask saveTask;
    private File file;

    public QuestManager(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        if (saveTask != null) saveTask.cancel();
        pool.clear();
        poolOrder.clear();
        var c = plugin.getConfig();
        enabled = c.getBoolean("quests.enabled", true);
        perDay = Math.max(1, c.getInt("quests.per-day", 3));
        for (Map<?, ?> m : c.getMapList("quests.pool")) {
            String id = Util.str(m.get("id"), null);
            if (id == null) continue;
            pool.put(id, new Def(id, Util.str(m.get("desc"), id), Util.str(m.get("type"), "kills").toLowerCase(Locale.ROOT),
                    Util.str(m.get("mob"), ""), (int) Util.num(m.get("target"), 100), (long) Util.num(m.get("cash"), 0),
                    (long) Util.num(m.get("tokens"), 0), (int) Util.num(m.get("min-kills"), 0)));
            poolOrder.add(id);
        }
        file = new File(plugin.getDataFolder(), "quests-data.yml");
        if (data.isEmpty() && file.exists()) load();
        if (enabled) saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    public void shutdown() {
        if (saveTask != null) saveTask.cancel();
        save();
    }

    private void load() {
        var y = YamlConfiguration.loadConfiguration(file);
        var root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            try {
                var s = root.getConfigurationSection(k);
                if (s == null) continue;
                Daily d = new Daily();
                d.day = s.getLong("day");
                d.ids.addAll(s.getStringList("ids"));
                for (int v : s.getIntegerList("progress")) d.progress.add(v);
                for (Boolean b : s.getBooleanList("done")) d.done.add(b);
                data.put(UUID.fromString(k), d);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        if (file == null) return;
        var y = new YamlConfiguration();
        long today = LocalDate.now().toEpochDay();
        for (var e : data.entrySet()) {
            Daily d = e.getValue();
            if (d.day != today) continue; // old days are dropped
            String p = "players." + e.getKey();
            y.set(p + ".day", d.day);
            y.set(p + ".ids", d.ids);
            y.set(p + ".progress", d.progress);
            y.set(p + ".done", d.done);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save quests: " + ex.getMessage()); }
    }

    /** Gives the player today's quests (picking new ones if the day changed). */
    private Daily ensure(Player p) {
        long today = LocalDate.now().toEpochDay();
        Daily d = data.get(p.getUniqueId());
        if (d != null && d.day == today && !d.ids.isEmpty()) return d;
        d = new Daily();
        d.day = today;
        long kills = 0;
        String raw = RankBonus.placeholder(p, "%objective_score_kills%");
        try { kills = Long.parseLong(raw == null ? "0" : raw.trim()); } catch (NumberFormatException ignored) { }
        List<String> eligible = new ArrayList<>();
        for (String id : poolOrder) if (pool.get(id).minKills() <= kills) eligible.add(id);
        if (eligible.isEmpty()) eligible.addAll(poolOrder);
        var rnd = ThreadLocalRandom.current();
        while (d.ids.size() < perDay && !eligible.isEmpty()) {
            String id = eligible.remove(rnd.nextInt(eligible.size()));
            d.ids.add(id);
            d.progress.add(0);
            d.done.add(false);
        }
        data.put(p.getUniqueId(), d);
        return d;
    }

    private void add(Player p, String type, String mob) {
        if (!enabled || pool.isEmpty()) return;
        Daily d = ensure(p);
        for (int i = 0; i < d.ids.size(); i++) {
            if (d.done.get(i)) continue;
            Def q = pool.get(d.ids.get(i));
            if (q == null || !q.type().equals(type)) continue;
            if (type.equals("mob") && !q.mob().equalsIgnoreCase(mob)) continue;
            int now = d.progress.get(i) + 1;
            d.progress.set(i, now);
            if (now >= q.target()) {
                d.done.set(i, true);
                if (q.cash() > 0) Util.console("eco give " + p.getName() + " " + q.cash());
                if (q.tokens() > 0) Util.console("points give " + p.getName() + " " + q.tokens());
                Util.tell(p, "&#A855F7[Quest] &aCompleted: &f" + q.desc() + " &7- &a$" + Util.money(q.cash())
                        + (q.tokens() > 0 ? " &7+ &b" + q.tokens() + " Bio Tokens" : ""));
            }
        }
    }

    public void onBossFight(Player p) { add(p, "boss", ""); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(MythicMobDeathEvent e) {
        if (!(e.getKiller() instanceof Player p)) return;
        String name = e.getMobType().getInternalName();
        if (name.toLowerCase(Locale.ROOT).startsWith("boss_")) return;
        add(p, "kills", "");
        add(p, "mob", name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { /* progress stays in memory and is saved periodically */ }

    public void show(Player p) {
        if (!enabled) { Util.tell(p, "&cQuests are turned off."); return; }
        Daily d = ensure(p);
        Util.tell(p, "&#A855F7&lDaily Quests &8- &7reset at midnight (server time)");
        for (int i = 0; i < d.ids.size(); i++) {
            Def q = pool.get(d.ids.get(i));
            if (q == null) continue;
            String reward = "&a$" + Util.money(q.cash()) + (q.tokens() > 0 ? " &7+ &b" + q.tokens() + " Bio Tokens" : "");
            if (d.done.get(i)) Util.tell(p, " &a✔ &7" + q.desc() + " &8- &adone");
            else Util.tell(p, " &8● &f" + q.desc() + " &7(" + d.progress.get(i) + "/" + q.target() + ") &8- " + reward);
        }
    }
}
