package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells players when they can afford the next rank, or can prestige. Once per rank/prestige, plus one gentle reminder.
 * Reads %prison_rankup_cost%, %prison_rankup_rank%, %vault_eco_balance% and %playerpoints_points% through PlaceholderAPI.
 */
public final class RankNotifier {
    private static final long[] P_CASH = {1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L,
            30_000_000_000L, 50_000_000_000L, 80_000_000_000L, 120_000_000_000L, 200_000_000_000L};
    private static final long[] P_TOKENS = {50, 100, 200, 350, 600, 900, 1300, 1900, 2400, 3000};

    private static final class State {
        String rankKey = ""; long rankAt; boolean rankReminded;
        int prestigeKey = -1; long prestigeAt; boolean prestigeReminded;
    }

    private final MobZonePlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;

    public RankNotifier(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        stop();
        if (!plugin.getConfig().getBoolean("notify.enabled", true)) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 100L, 60L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        states.clear();
    }

    private boolean inWorld(Player p) {
        List<String> l = plugin.getConfig().getStringList("notify.worlds");
        if (l.isEmpty()) l = List.of("prison");
        for (String w : l) if (w.equalsIgnoreCase(p.getWorld().getName())) return true;
        return false;
    }

    static double num(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long remindMs = plugin.getConfig().getLong("notify.reminder-minutes", 5) * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inWorld(p)) continue;
            double cash = num(RankBonus.placeholder(p, "%vault_eco_balance%"));
            if (Double.isNaN(cash)) continue;
            State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());

            if (p.hasPermission("group.rank_z")) {
                // top rank: prestige
                int level = RankBonus.prestigeLevel(p, RankBonus.held(p));
                if (level >= 10) continue;
                double tokens = num(RankBonus.placeholder(p, "%playerpoints_points%"));
                boolean ok = cash >= P_CASH[level] && (Double.isNaN(tokens) || tokens >= P_TOKENS[level]);
                if (!ok) continue;
                if (st.prestigeKey != level) {
                    st.prestigeKey = level; st.prestigeAt = now; st.prestigeReminded = false;
                    send(p, "&a&l[Prestige] &7You can prestige! Type &f/prestige &7and SHIFT + LEFT-CLICK to confirm.", "&aYou can prestige!");
                } else if (!st.prestigeReminded && remindMs > 0 && now - st.prestigeAt >= remindMs) {
                    st.prestigeReminded = true;
                    p.sendMessage(Util.c("&a&l[Prestige] &7Reminder: you can still prestige. Type &f/prestige"));
                }
                continue;
            }

            String rank = RankBonus.placeholder(p, "%prison_rank%");
            String next = RankBonus.placeholder(p, "%prison_rankup_rank%");
            double cost = num(RankBonus.placeholder(p, "%prison_rankup_cost%"));
            if (rank == null || next == null || next.isBlank() || Double.isNaN(cost) || cost <= 0 || cash < cost) continue;
            if (!rank.equals(st.rankKey)) {
                st.rankKey = rank; st.rankAt = now; st.rankReminded = false;
                send(p, "&a&l[Rank] &7You can rank up to &f" + next + "&7! Type &f/rankup", "&aYou can rank up to " + next + "!");
            } else if (!st.rankReminded && remindMs > 0 && now - st.rankAt >= remindMs) {
                st.rankReminded = true;
                p.sendMessage(Util.c("&a&l[Rank] &7Reminder: you can still rank up to &f" + next + "&7. Type &f/rankup"));
            }
        }
        states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void send(Player p, String chat, String bar) {
        p.sendMessage(Util.c(chat));
        p.sendActionBar(Util.c(bar));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
    }
}
