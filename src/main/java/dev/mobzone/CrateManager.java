package dev.mobzone;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Bio Token crates. Opened with: /mobzone crate open <crate> <player> (the /crates menu calls this). */
public class CrateManager {
    record Reward(int weight, String type, long amount, double mult, int minutes,
                          String cmd, String text, boolean broadcast) {}

    record Crate(String id, String name, long cost, List<Reward> rewards, int[] weights) {}

    private record Booster(double mult, long until) {}

    private final MobZonePlugin plugin;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<UUID, Booster> boosters = new HashMap<>();

    public CrateManager(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        crates.clear();
        var root = plugin.getConfig().getConfigurationSection("crates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            List<Reward> rewards = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("rewards")) {
                rewards.add(new Reward((int) Util.num(m.get("weight"), 1), Util.str(m.get("type"), "cash").toLowerCase(Locale.ROOT),
                        (long) Util.num(m.get("amount"), 0), Util.num(m.get("multiplier"), 1.0),
                        (int) Util.num(m.get("minutes"), 10), Util.str(m.get("command"), ""),
                        Util.str(m.get("text"), ""), Boolean.parseBoolean(Util.str(m.get("broadcast"), "false"))));
            }
            if (rewards.isEmpty()) continue;
            int[] w = new int[rewards.size()];
            for (int i = 0; i < w.length; i++) w[i] = rewards.get(i).weight();
            crates.put(id.toLowerCase(Locale.ROOT), new Crate(id.toLowerCase(Locale.ROOT),
                    s.getString("name", id), s.getLong("cost", 10), rewards, w));
        }
    }

    Crate crate(String id) { return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT)); }

    public java.util.Set<String> ids() { return crates.keySet(); }

    /** Personal cash booster multiplier for this player (1.0 when none is active). */
    public double cashMultiplier(Player p) {
        Booster b = boosters.get(p.getUniqueId());
        if (b == null) return 1.0;
        if (System.currentTimeMillis() >= b.until()) { boosters.remove(p.getUniqueId()); return 1.0; }
        return b.mult();
    }

    public void open(Player p, String crateId) {
        Crate c = crates.get(crateId.toLowerCase(Locale.ROOT));
        if (c == null) { Util.tell(p, "&cThat crate doesn't exist."); return; }
        String raw = RankBonus.placeholder(p, "%playerpoints_points%");
        long bal;
        try { bal = Long.parseLong(raw == null ? "" : raw.replace(",", "").trim()); }
        catch (NumberFormatException e) { Util.tell(p, "&cCouldn't read your Bio Tokens. Tell an admin."); return; }
        if (bal < c.cost()) {
            Util.tell(p, "&cYou need &b" + c.cost() + " Bio Tokens &cfor this crate. You have &b" + bal + "&c.");
            return;
        }
        Util.console("points take " + p.getName() + " " + c.cost());
        Reward r = c.rewards().get(CrateMath.pick(c.weights(), ThreadLocalRandom.current().nextDouble()));
        String text = r.text().isEmpty() ? describe(r) : r.text();
        give(p, r);
        Util.tell(p, "&#A3E635[Crate] &7You opened " + c.name() + "&7 and won &f" + text + "&7!" + (r.type().equals("cash") ? " &7Run &f/claim &7to collect the cash." : ""));
        if (r.broadcast())
            Util.broadcast("&#A3E635[Crate] &f" + p.getName() + " &7won &f" + text + " &7from " + c.name() + "&7!");
    }

    static String describe(Reward r) {
        return switch (r.type()) {
            case "cash" -> "&a$" + Util.money(r.amount());
            case "tokens" -> "&b" + r.amount() + " Bio Tokens";
            case "booster" -> "&6" + r.mult() + "x cash for " + r.minutes() + " min";
            default -> "a prize";
        };
    }

    void give(Player p, Reward r) {
        switch (r.type()) {
            case "cash" -> plugin.chests().addPending(p.getUniqueId(), r.amount());
            case "tokens" -> Util.console("points give " + p.getName() + " " + r.amount());
            case "booster" -> boosters.put(p.getUniqueId(),
                    new Booster(r.mult(), System.currentTimeMillis() + r.minutes() * 60_000L));
            case "command" -> Util.console(r.cmd().replace("{player}", p.getName()));
            default -> { }
        }
    }
}
