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
 * You can give roles up to your own rank and change people below you (owners can change anyone).
 * /promote <player> <role> gives exactly that role (any role, even owner, if you are owner or console).
 */
public final class StaffCommands implements CommandExecutor, TabCompleter {
    static final List<String> ROLES = List.of("helper", "mod", "admin", "manager", "coowner", "owner");

    private final MobZonePlugin plugin;

    public StaffCommands(MobZonePlugin plugin) { this.plugin = plugin; }

    /** Highest staff role a player is actually IN (their LuckPerms groups), -1 for none. Permissions are not used, because OP and wildcards would make everyone look like owner. */
    static int roleOf(Player p) {
        String raw = RankBonus.placeholder(p, "%luckperms_groups%");
        int best = -1;
        if (raw != null) {
            for (String g : raw.split("[,\\s]+")) {
                int i = ROLES.indexOf(g.trim().toLowerCase(Locale.ROOT));
                if (i > best) best = i;
            }
        }
        return best;
    }

    private static String pretty(int i) { return i < 0 ? "no staff role" : ROLES.get(i); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("keyall")) { keyAll(s, a); return true; }
        if (a.length < 1) { Util.tell(s, c.equals("promote") ? "&e/promote <player> [role]   roles: " + String.join(", ", ROLES) : "&e/demote <player>"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { Util.tell(s, "&cThat player must be online."); return true; }
        int cur = roleOf(t);
        int mine = s instanceof Player sp ? roleOf(sp) : ROLES.size();   // console is above everyone. OP does NOT make anyone owner.
        boolean up = c.equals("promote");
        int owner = ROLES.size() - 1;
        int next;
        if (up && a.length > 1) {                       // /promote NAME <role>  = give exactly that role
            next = ROLES.indexOf(a[1].toLowerCase(Locale.ROOT));
            if (next < 0) { Util.tell(s, "&cUnknown role. Roles: " + String.join(", ", ROLES)); return true; }
            if (next == cur) { Util.tell(s, "&e" + t.getName() + " already is " + pretty(cur) + "."); return true; }
        } else {
            next = up ? cur + 1 : cur - 1;
            if (up && next > owner) { Util.tell(s, "&e" + t.getName() + " is already the highest role."); return true; }
            if (!up && cur < 0) { Util.tell(s, "&c" + t.getName() + " isn't staff."); return true; }
        }
        // who can change whom: never someone above you; equal rank only for owners; roles up to your own
        if (cur > mine || (cur == mine && mine < owner)) { Util.tell(s, "&cYou can't change someone of your own rank or higher."); return true; }
        if (next > mine) { Util.tell(s, "&cYou can only give roles up to your own."); return true; }
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
        } else if (cmd.getName().equalsIgnoreCase("promote") && a.length == 2) {
            out.addAll(ROLES);
        } else if (a.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String pre = a.length > 0 ? a[a.length - 1].toLowerCase(Locale.ROOT) : "";
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }
}
