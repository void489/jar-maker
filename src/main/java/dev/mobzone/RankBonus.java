package dev.mobzone;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** Adds a cash bonus on top of the MythicMobs payout, based on the killer's rank (rank_a..rank_z). */
public class RankBonus implements Listener {
    private final MobZonePlugin plugin;
    private boolean enabled;
    private double percentPerRank;
    private double percentPerPrestige;
    private boolean payBase;
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
        payBase = c.getBoolean("rank-bonus.pay-base", true);
        percentPerPrestige = c.getDouble("rank-bonus.percent-per-prestige", 10.0);
        payouts.clear();
        for (Object[] d : DEFAULTS) payouts.put((String) d[0], ((Integer) d[1]).longValue());
        var sec = c.getConfigurationSection("rank-bonus.payouts");
        if (sec != null) for (String k : sec.getKeys(false)) payouts.put(k.toLowerCase(Locale.ROOT), sec.getLong(k));
    }

    /** Explicitly held permission nodes only (so a wildcard '*' or OP does not count as every group). */
    static java.util.Set<String> held(Player p) {
        java.util.Set<String> out = new HashSet<>();
        for (var i : p.getEffectivePermissions()) if (i.getValue()) out.add(i.getPermission().toLowerCase(Locale.ROOT));
        return out;
    }

    /** A=1 ... Z=26, 0 if the player has no rank group. */
    static int rankIndex(java.util.Set<String> perms) {
        for (int i = 26; i >= 1; i--) if (perms.contains("group.rank_" + (char) ('a' + i - 1))) return i;
        return 0;
    }

    private static java.lang.reflect.Method papi;
    private static boolean papiTried;

    /** Resolves a PlaceholderAPI placeholder for a player, or null if PlaceholderAPI is unavailable. */
    static String placeholder(Player p, String text) {
        try {
            if (!papiTried) {
                papiTried = true;
                if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                            .getMethod("setPlaceholders", Player.class, String.class);
            }
            return papi == null ? null : (String) papi.invoke(null, p, text);
        } catch (Throwable t) { return null; }
    }

    /** Rank number 1..26 using the same source as the scoreboard (%prison_rank%), falling back to groups. */
    static int rank(Player p, java.util.Set<String> perms) {
        String r = placeholder(p, "%prison_rank%");
        if (r != null) {
            r = r.replaceAll("(?i)&[0-9a-fk-or]|§.", "").trim();
            if (r.length() == 1 && Character.isLetter(r.charAt(0))) {
                int i = Character.toUpperCase(r.charAt(0)) - 'A' + 1;
                if (i >= 1 && i <= 26) return i;
            }
        }
        return rankIndex(perms);
    }

    /** Prestige level 0..10 using the same source as the scoreboard (LuckPerms meta), or the groups, whichever is higher. */
    static int prestigeLevel(Player p, java.util.Set<String> perms) {
        int g = prestige(perms), m = 0;
        String v = placeholder(p, "%luckperms_meta_prestige%");
        if (v != null) { try { m = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { } }
        return Math.max(g, Math.min(10, Math.max(0, m)));
    }

    static int prestige(java.util.Set<String> perms) {
        for (int i = 10; i >= 1; i--) if (perms.contains("group.prestige_" + i)) return i;
        return 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(MythicMobDeathEvent e) {
        if (!enabled) return;
        if (!(e.getKiller() instanceof Player p)) return;
        Long base = payouts.get(e.getMobType().getInternalName().toLowerCase(Locale.ROOT));
        if (base == null) return;
        var perms = held(p);
        int idx = rank(p, perms);
        double pct = percentPerRank * Math.max(0, idx - 1) + percentPerPrestige * prestigeLevel(p, perms);
        long extra = Math.round(base * pct / 100.0);
        long total = (payBase ? base : 0L) + extra;
        if (total <= 0) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + p.getName() + " " + total);
    }
}
