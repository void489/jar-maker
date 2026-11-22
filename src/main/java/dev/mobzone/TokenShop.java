package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Permanent perks bought with Bio Tokens from /tokenshop (global, works in every world). Keys are sold by the crates code. */
final class TokenShop {
    private record Perk(String id, String name, long cost, String permission) {}

    private final MobZonePlugin plugin;

    TokenShop(MobZonePlugin plugin) { this.plugin = plugin; }

    private Map<String, Perk> perks() {
        Map<String, Perk> m = new LinkedHashMap<>();
        var list = plugin.getConfig().getMapList("tokenshop.perks");
        if (list.isEmpty()) {
            m.put("nick", new Perk("nick", "Nicknames (/nick)", 150, "essentials.nick"));
            m.put("hat", new Perk("hat", "Hats (/hat)", 100, "essentials.hat"));
            m.put("workbench", new Perk("workbench", "Portable crafting table (/workbench)", 200, "essentials.workbench"));
            m.put("enderchest", new Perk("enderchest", "Portable ender chest (/enderchest)", 400, "essentials.enderchest"));
        } else for (Map<?, ?> x : list) {
            String id = Util.str(x.get("id"), null);
            if (id != null) m.put(id, new Perk(id, Util.str(x.get("name"), id), (long) Util.num(x.get("cost"), 100), Util.str(x.get("permission"), "")));
        }
        return m;
    }

    /** /mobzone perk buy <player> <perk> (called from the menu as console). */
    void command(CommandSender sender, String[] a) {
        if (a.length < 4 || !a[1].equalsIgnoreCase("buy")) {
            Util.tell(sender, "&e/mobzone perk buy <player> <" + String.join("|", perks().keySet()) + ">");
            return;
        }
        Player p = Bukkit.getPlayerExact(a[2]);
        Perk perk = perks().get(a[3].toLowerCase(java.util.Locale.ROOT));
        if (p == null) { Util.tell(sender, "&cThat player isn't online."); return; }
        if (perk == null || perk.permission().isBlank()) { Util.tell(sender, "&cUnknown perk."); return; }
        if (p.hasPermission(perk.permission())) { Util.tell(p, "&#A3E635[Tokens] &7You already own &f" + perk.name() + "&7."); return; }
        String raw = RankBonus.placeholder(p, "%playerpoints_points%");
        long have = 0;
        try { have = (long) Double.parseDouble(raw == null ? "0" : raw.replace(",", "").trim()); } catch (NumberFormatException ignored) { }
        if (have < perk.cost()) { Util.tell(p, "&cYou need &b" + perk.cost() + " Bio Tokens &cfor that. You have &b" + have + "&c."); return; }
        Util.console("points take " + p.getName() + " " + perk.cost());
        Util.console("lp user " + p.getName() + " permission set " + perk.permission() + " true");
        Util.tell(p, "&#A3E635[Tokens] &aYou bought &f" + perk.name() + " &afor &b" + perk.cost() + " Bio Tokens&a. It is yours for good.");
    }
}
