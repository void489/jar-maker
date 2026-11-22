package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bio Token sources in Oneblock: a reward the first time a player's island reaches each phase,
 * rewards at island-level milestones, and daily quests (break blocks, kill mobs, sell items).
 * Phase and island level are read through PlaceholderAPI, so the names are set in config.yml (oneblock-rewards:).
 * Everything runs only for players in an economy world of mode "oneblock".
 */
public class OneblockRewards implements Listener {
    private record Quest(String id, String desc, String type, int target, long cash, long tokens) {}

    private static final class Daily {
        long day;
        final List<String> ids = new ArrayList<>();
        final List<Integer> progress = new ArrayList<>();
        final List<Boolean> done = new ArrayList<>();
    }

    private static final class Prog {
        final Set<String> phases = new HashSet<>();
        final Set<Integer> levels = new HashSet<>();
        Daily daily;
    }

    private final MobZonePlugin plugin;
    private final Map<UUID, Prog> data = new HashMap<>();
    private final Map<String, Quest> pool = new LinkedHashMap<>();
    private final Map<Integer, Long> levelRewards = new LinkedHashMap<>();
    private final Map<String, Long> phaseRewards = new HashMap<>();
    private boolean enabled = true, warnedPhase, warnedLevel;
    private long phaseDefault = 10;
    private int perDay = 3;
    private String phasePh = "%aoneblock_my_island_phase%", levelPh = "%Level_oneblock_island_level%";
    private BukkitTask poll, saveTask;
    private File file;

    public OneblockRewards(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        if (poll != null) poll.cancel();
        if (saveTask != null) saveTask.cancel();
        pool.clear(); levelRewards.clear(); phaseRewards.clear();
        var c = plugin.getConfig();
        enabled = c.getBoolean("oneblock-rewards.enabled", true);
        phasePh = c.getString("oneblock-rewards.phase-placeholder", "%aoneblock_my_island_phase%");
        levelPh = c.getString("oneblock-rewards.level-placeholder", "%Level_oneblock_island_level%");
        phaseDefault = c.getLong("oneblock-rewards.phase-default-tokens", 10);
        perDay = Math.max(1, c.getInt("oneblock-rewards.quests-per-day", 3));
        var ph = c.getConfigurationSection("oneblock-rewards.phase-tokens");
        if (ph != null) for (String k : ph.getKeys(false)) phaseRewards.put(norm(k), ph.getLong(k));
        List<Map<?, ?>> ls = c.getMapList("oneblock-rewards.level-milestones");
        if (ls.isEmpty()) {
            int[][] def = {{10, 5}, {25, 10}, {50, 15}, {100, 25}, {200, 40}, {350, 60}, {500, 80}, {750, 120}, {1000, 200}};
            for (int[] d : def) levelRewards.put(d[0], (long) d[1]);
        } else for (Map<?, ?> m : ls) levelRewards.put((int) Util.num(m.get("level"), 0), (long) Util.num(m.get("tokens"), 0));
        List<Map<?, ?>> qs = c.getMapList("oneblock-rewards.quest-pool");
        if (qs.isEmpty()) {
            Object[][] def = {{"blocks_100", "Break 100 blocks", "blocks", 100, 500, 1}, {"blocks_300", "Break 300 blocks", "blocks", 300, 1500, 2},
                    {"blocks_1000", "Break 1,000 blocks", "blocks", 1000, 5000, 4}, {"mobs_20", "Kill 20 mobs", "mobs", 20, 1000, 1},
                    {"mobs_50", "Kill 50 mobs", "mobs", 50, 3000, 3}, {"sell_200", "Sell 200 items", "sell", 200, 1500, 2},
                    {"sell_1000", "Sell 1,000 items", "sell", 1000, 8000, 5}};
            for (Object[] d : def) pool.put((String) d[0], new Quest((String) d[0], (String) d[1], (String) d[2], (int) d[3], (long) (int) d[4], (long) (int) d[5]));
        } else for (Map<?, ?> m : qs) {
            String id = Util.str(m.get("id"), null);
            if (id != null) pool.put(id, new Quest(id, Util.str(m.get("desc"), id), Util.str(m.get("type"), "blocks").toLowerCase(Locale.ROOT),
                    (int) Util.num(m.get("target"), 100), (long) Util.num(m.get("cash"), 0), (long) Util.num(m.get("tokens"), 0)));
        }
        file = new File(plugin.getDataFolder(), "oneblock-rewards-data.yml");
        if (data.isEmpty() && file.exists()) load();
        if (enabled) {
            long secs = Math.max(10, c.getInt("oneblock-rewards.poll-seconds", 30));
            poll = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 200L, secs * 20L);
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
        }
    }

    public void shutdown() {
        if (poll != null) poll.cancel();
        if (saveTask != null) saveTask.cancel();
        save();
    }

    private static String norm(String s) { return org.bukkit.ChatColor.stripColor(s == null ? "" : s).trim().toLowerCase(Locale.ROOT); }

    private boolean inOneblock(Player p) { return "oneblock".equals(plugin.market().modeFor(p)); }

    private Prog prog(UUID id) { return data.computeIfAbsent(id, k -> new Prog()); }

    private void load() {
        var y = YamlConfiguration.loadConfiguration(file);
        var root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            try {
                var s = root.getConfigurationSection(k);
                Prog p = new Prog();
                p.phases.addAll(s.getStringList("phases"));
                p.levels.addAll(s.getIntegerList("levels"));
                if (s.contains("daily.day")) {
                    Daily d = new Daily();
                    d.day = s.getLong("daily.day");
                    d.ids.addAll(s.getStringList("daily.ids"));
                    for (int v : s.getIntegerList("daily.progress")) d.progress.add(v);
                    for (Boolean b : s.getBooleanList("daily.done")) d.done.add(b);
                    p.daily = d;
                }
                data.put(UUID.fromString(k), p);
            } catch (Exception ignored) { }
        }
    }

    public void save() {
        if (file == null) return;
        var y = new YamlConfiguration();
        long today = LocalDate.now().toEpochDay();
        for (var e : data.entrySet()) {
            String b = "players." + e.getKey();
            Prog p = e.getValue();
            y.set(b + ".phases", new ArrayList<>(p.phases));
            y.set(b + ".levels", new ArrayList<>(p.levels));
            if (p.daily != null && p.daily.day == today) {
                y.set(b + ".daily.day", p.daily.day);
                y.set(b + ".daily.ids", p.daily.ids);
                y.set(b + ".daily.progress", p.daily.progress);
                y.set(b + ".daily.done", p.daily.done);
            }
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save Oneblock rewards: " + ex.getMessage()); }
    }

    public void resetPlayer(UUID id) { data.remove(id); save(); }

    public void resetAll() { data.clear(); save(); }

    private void giveTokens(Player p, long n) { if (n > 0) Util.console("points give " + p.getName() + " " + n); }

    // ---------------------------------------------------------------- phases and island level
    private static boolean unresolved(String s) { return s == null || s.isBlank() || s.contains("%"); }

    private void sweep() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inOneblock(p)) continue;
            Prog pr = prog(p.getUniqueId());
            String ph = RankBonus.placeholder(p, phasePh);
            if (unresolved(ph)) {
                if (!warnedPhase) { warnedPhase = true; plugin.getLogger().warning("Oneblock phase rewards: placeholder " + phasePh + " does not resolve. Run /mobzone obrewards to see it, then fix oneblock-rewards.phase-placeholder."); }
            } else {
                String key = norm(ph);
                if (!key.isEmpty() && pr.phases.add(key)) {
                    long t = phaseRewards.getOrDefault(key, phaseDefault);
                    giveTokens(p, t);
                    if (t > 0) Util.tell(p, "&#A3E635[Oneblock] &aNew phase reached: &f" + org.bukkit.ChatColor.stripColor(ph) + " &7- &b+" + t + " Bio Tokens");
                }
            }
            String lv = RankBonus.placeholder(p, levelPh);
            if (unresolved(lv)) {
                if (!warnedLevel) { warnedLevel = true; plugin.getLogger().warning("Oneblock level rewards: placeholder " + levelPh + " does not resolve. Run /mobzone obrewards to see it, then fix oneblock-rewards.level-placeholder."); }
            } else {
                long level;
                try { level = (long) Double.parseDouble(lv.replace(",", "").trim()); } catch (NumberFormatException ex) { continue; }
                for (var e : levelRewards.entrySet())
                    if (level >= e.getKey() && pr.levels.add(e.getKey())) {
                        giveTokens(p, e.getValue());
                        Util.tell(p, "&#A3E635[Oneblock] &aIsland level " + e.getKey() + " reached! &7- &b+" + e.getValue() + " Bio Tokens");
                    }
            }
        }
    }

    public void debug(CommandSender s) {
        if (!(s instanceof Player p)) { Util.tell(s, "&cPlayers only."); return; }
        Util.tell(s, "&7Oneblock world: &f" + inOneblock(p) + " &7(mode " + plugin.market().modeFor(p) + ")");
        Util.tell(s, "&7phase " + phasePh + " = &f" + RankBonus.placeholder(p, phasePh));
        Util.tell(s, "&7level " + levelPh + " = &f" + RankBonus.placeholder(p, levelPh));
        Prog pr = prog(p.getUniqueId());
        Util.tell(s, "&7phases already rewarded: &f" + pr.phases + " &7levels: &f" + pr.levels);
        Util.tell(s, "&7If a value shows as %...%, run &f/papi list &7to find the right placeholder and set it in config.yml.");
    }

    // ---------------------------------------------------------------- daily quests
    private Daily ensure(Player p) {
        Prog pr = prog(p.getUniqueId());
        long today = LocalDate.now().toEpochDay();
        if (pr.daily != null && pr.daily.day == today && !pr.daily.ids.isEmpty()) return pr.daily;
        Daily d = new Daily();
        d.day = today;
        List<String> ids = new ArrayList<>(pool.keySet());
        var rnd = ThreadLocalRandom.current();
        while (d.ids.size() < perDay && !ids.isEmpty()) {
            d.ids.add(ids.remove(rnd.nextInt(ids.size())));
            d.progress.add(0);
            d.done.add(false);
        }
        pr.daily = d;
        return d;
    }

    private void add(Player p, String type, int amount) {
        if (!enabled || pool.isEmpty() || !inOneblock(p)) return;
        Daily d = ensure(p);
        for (int i = 0; i < d.ids.size(); i++) {
            if (d.done.get(i)) continue;
            Quest q = pool.get(d.ids.get(i));
            if (q == null || !q.type().equals(type)) continue;
            int now = d.progress.get(i) + amount;
            d.progress.set(i, now);
            if (now >= q.target()) {
                d.done.set(i, true);
                if (q.cash() > 0) Util.console("eco give " + p.getName() + " " + q.cash());
                giveTokens(p, q.tokens());
                Util.tell(p, "&#A3E635[Quest] &aCompleted: &f" + q.desc() + " &7- &a$" + Util.money(q.cash())
                        + (q.tokens() > 0 ? " &7+ &b" + q.tokens() + " Bio Tokens" : ""));
            }
        }
    }

    /** Called by the market when a player sells items. */
    public void onSold(Player p, int items) { if (items > 0) add(p, "sell", items); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { add(e.getPlayer(), "blocks", 1); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Mob && e.getEntity().getKiller() != null) add(e.getEntity().getKiller(), "mobs", 1);
    }

    public void show(Player p) {
        if (!enabled) { Util.tell(p, "&cQuests are turned off."); return; }
        Daily d = ensure(p);
        Util.tell(p, "&#A3E635&lOneblock Daily Quests &8- &7reset at midnight (server time)");
        for (int i = 0; i < d.ids.size(); i++) {
            Quest q = pool.get(d.ids.get(i));
            if (q == null) continue;
            String reward = "&a$" + Util.money(q.cash()) + (q.tokens() > 0 ? " &7+ &b" + q.tokens() + " Bio Tokens" : "");
            if (d.done.get(i)) Util.tell(p, " &a✔ &7" + q.desc() + " &8- &adone");
            else Util.tell(p, " &8● &f" + q.desc() + " &7(" + d.progress.get(i) + "/" + q.target() + ") &8- " + reward);
        }
    }
}
