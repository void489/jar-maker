package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Boss bar at the top of the screen: kills toward the next zone gate. Reads the 'kills' scoreboard objective. */
public final class KillTracker {
    static final String[] NAMES = {"Cell Block", "Cafeteria", "Laundry", "Infirmary", "Prison Yard", "Sewers", "Lab", "Quarantine", "Sub-basement"};
    static final int[] KILLS = {100, 300, 700, 1500, 3500, 6000, 12000, 22000, 40000};

    private final MobZonePlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask task;

    public KillTracker(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        stop();
        if (!plugin.getConfig().getBoolean("tracker.enabled", true)) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (BossBar b : bars.values()) b.removeAll();
        bars.clear();
    }

    private boolean inWorld(Player p) {
        var l = plugin.getConfig().getStringList("tracker.worlds");
        if (l.isEmpty()) l = java.util.List.of("prison");
        for (String w : l) if (w.equalsIgnoreCase(p.getWorld().getName())) return true;
        return false;
    }

    static String line(int kills, double[] progressOut) {
        int next = -1;
        for (int i = 0; i < KILLS.length; i++) if (kills < KILLS[i]) { next = i; break; }
        if (next < 0) { progressOut[0] = 1.0; return "&a&lAll zones unlocked"; }
        int prev = next == 0 ? 0 : KILLS[next - 1];
        progressOut[0] = Math.max(0.0, Math.min(1.0, (kills - prev) / (double) (KILLS[next] - prev)));
        return "&dNext zone: &f" + NAMES[next] + " &8| &f" + kills + "&7/&f" + KILLS[next] + " &7kills";
    }

    private void tick() {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("kills");
        for (Iterator<Map.Entry<UUID, BossBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next(); Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || !inWorld(p)) { e.getValue().removeAll(); it.remove(); }
        }
        if (o == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inWorld(p)) continue;
            int kills = o.getScore(p.getName()).getScore();
            double[] prog = new double[1];
            String text = org.bukkit.ChatColor.translateAlternateColorCodes('&', line(kills, prog));
            BossBar b = bars.get(p.getUniqueId());
            if (b == null) {
                b = Bukkit.createBossBar(text, BarColor.PURPLE, BarStyle.SEGMENTED_10);
                b.addPlayer(p);
                bars.put(p.getUniqueId(), b);
            }
            b.setTitle(text);
            b.setProgress(prog[0]);
        }
    }
}
