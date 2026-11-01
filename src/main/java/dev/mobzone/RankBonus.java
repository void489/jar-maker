package dev.mobzone;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Adds a cash bonus on top of the MythicMobs payout, based on the killer's rank (rank_a..rank_z). */
public class RankBonus implements Listener {
    private final MobZonePlugin plugin;
    private boolean enabled;
    private double percentPerRank;
    private final Map<String, Long> payouts = new HashMap<>();

    private static final Object[][] DEFAULTS = {
            {"walker", 50}, {"runner", 40}, {"brute", 150}, {"cook", 210}, {"stoker", 275},
            {"exploder", 240}, {"spitter", 350}, {"scientist", 425}, {"guard", 500},
            {"crawler", 600}, {"mutant", 900}, {"hazmat", 1300}, {"elite", 1900}};

    public RankBonus(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("rank-bonus.enabled", true);
        percentPerRank = c.getDouble("rank-bonus.percent-per-rank", 2.0);
        payouts.clear();
        for (Object[] d : DEFAULTS) payouts.put((String) d[0], ((Integer) d[1]).longValue());
        var sec = c.getConfigurationSection("rank-bonus.payouts");
        if (sec != null) for (String k : sec.getKeys(false)) payouts.put(k.toLowerCase(Locale.ROOT), sec.getLong(k));
    }

    /** A=1 ... Z=26, 0 if the player has no rank group. Uses LuckPerms group permissions (inherited). */
    static int rankIndex(Player p) {
        for (int i = 26; i >= 1; i--) {
            if (p.hasPermission("group.rank_" + (char) ('a' + i - 1))) return i;
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(MythicMobDeathEvent e) {
        if (!enabled) return;
        if (!(e.getKiller() instanceof Player p)) return;
        Long base = payouts.get(e.getMobType().getInternalName().toLowerCase(Locale.ROOT));
        if (base == null) return;
        int idx = rankIndex(p);
        if (idx <= 1) return; // rank A (or none) = no bonus
        long extra = Math.round(base * percentPerRank * (idx - 1) / 100.0);
        if (extra <= 0) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + p.getName() + " " + extra);
    }
}
