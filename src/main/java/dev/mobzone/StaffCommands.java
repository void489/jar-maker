package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /promote <player>   one staff role up        (helper > mod > admin > manager > coowner > owner)
 * /demote <player>    one staff role down      (from helper they lose staff completely)
 * /keyall <crate|all> [amount]   keys for everyone online
 * You can only promote someone to a role BELOW your own, and only demote someone who is below you.
 * Console can do anything. Owner is never given by /promote (use LuckPerms for that on purpose).
 */
public final class StaffCommands implements CommandExecutor, TabCompleter {
    static final List<String> ROLES = List.of("helper", "mod", "admin", "manager", "coowner", "owner");

    private final MobZonePlugin plugin;

    public StaffCommands(MobZonePlugin plugin) { this.plugin = plugin; }

    /** Highest staff role index a player holds, -1 for none. Uses permissions, so the player must be online. */
    private static int roleOf(Player p) {
        for (int i = ROLES.size() - 1; i >= 0; i--) if (p.hasPermission("group." + ROLES.get(i))) return i;
        return -1;
    }

    private static String pretty(int i) { return i < 0 ? "no staff role" : ROLES.get(i); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("keyall")) { keyAll(s, a); return true; }
        if (a.length < 1) { Util.tell(s, "&e/" + c + " <player>"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { Util.tell(s, "&cThat player must be online."); return true; }
        int cur = roleOf(t);
        int mine = s instanceof Player sp ? roleOf(sp) : ROLES.size();   // console is above everyone
        boolean up = c.equals("promote");
        int next = up ? cur + 1 : cur - 1;
        if (up && next >= ROLES.size() - 1) { Util.tell(s, "&cOwner can't be given with /promote. Use /lp user " + t.getName() + " parent add owner on purpose."); return true; }
        if (!up && cur < 0) { Util.tell(s, "&c" + t.getName() + " isn't staff."); return true; }
        if (cur >= mine) { Util.tell(s, "&cYou can't change someone of your own rank or higher."); return true; }
        if (up && next >= mine) { Util.tell(s, "&cYou can only promote people to a role below yours."); return true; }
        String n = t.getName();
        if (cur >= 0) Util.console("lp user " + n + " parent remove " + ROLES.get(cur));
        if (next >= 0) {
            Util.console("lp user " + n + " parent add " + ROLES.get(next));
            if (cur < 0) {   // first time on staff: let them keep their game mode per world
                Util.console("lp user " + n + " permission set mobzone.keepgamemode true");
                Util.console("lp user " + n + " permission set mv.bypass.gamemode.* true");
            }
        } else {
            Util.console("lp user " + n + " permission unset mobzone.keepgamemode");
            Util.console("lp user " + n + " permission unset mv.bypass.gamemode.*");
        }
        Util.tell(s, "&a" + n + ": " + pretty(cur) + " -> " + pretty(next));
        Util.tell(t, up ? "&#A3E635[Staff] &7You were promoted to &f" + pretty(next) + "&7!" : "&#A3E635[Staff] &7You are now &f" + pretty(next) + "&7.");
        return true;
    }

    private void keyAll(CommandSender s, String[] a) {
        if (a.length < 1) { Util.tell(s, "&e/keyall <" + String.join("|", plugin.crates().ids()) + "|all> [amount]"); return; }
        int n = 1;
        if (a.length > 1) {
            try { n = Integer.parseInt(a[1]); } catch (NumberFormatException e) { Util.tell(s, "&cAmount must be a number."); return; }
        }
        if (n < 1 || n > 1000) { Util.tell(s, "&cAmount must be 1 to 1000."); return; }
        List<String> crates = new ArrayList<>();
        if (a[0].equalsIgnoreCase("all")) crates.addAll(plugin.crates().ids());
        else if (plugin.crates().crate(a[0]) != null) crates.add(a[0].toLowerCase(Locale.ROOT));
        else { Util.tell(s, "&cNo such crate. Crates: " + String.join(", ", plugin.crates().ids()) + ", all"); return; }
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String id : crates) plugin.chests().addKeys(p.getUniqueId(), id, n);
            count++;
        }
        String what = a[0].equalsIgnoreCase("all") ? "every" : plugin.crates().crate(crates.get(0)).name();
        Util.broadcastAll("&#A3E635[Keys] &f" + s.getName() + " &7gave everyone online &f" + n + "x " + what + " &7key" + (n > 1 ? "s" : "") + "! Open them in the lobby crates.");
        Util.tell(s, "&aGave keys to " + count + " players.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("keyall")) {
            if (a.length == 1) { out.addAll(plugin.crates().ids()); out.add("all"); }
        } else if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase(Locale.ROOT) : "";
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }
}
