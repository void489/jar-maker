package dev.mobzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

/** /announce <world|all> <message>: big title on everyone's screen in that world. */
public final class Announce implements CommandExecutor, TabCompleter {
    private final MobZonePlugin plugin;
    public Announce(MobZonePlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("mobzone.admin")) { Util.tell(s, "&cNo permission."); return true; }
        if (a.length < 2) { Util.tell(s, "&7Use &f/announce <world|all> <message>&7. Worlds: " + String.join(", ", worlds()) + "."); return true; }
        String target = a[0].toLowerCase(Locale.ROOT);
        String msg = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        List<Player> to = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String w = p.getWorld().getName().toLowerCase(Locale.ROOT);
            if (target.equals("all") || w.equals(target) || w.startsWith(target + "_")) to.add(p);
        }
        if (!target.equals("all") && Bukkit.getWorlds().stream().noneMatch(w -> w.getName().toLowerCase(Locale.ROOT).startsWith(target))) {
            Util.tell(s, "&cNo such world. Worlds: " + String.join(", ", worlds()) + ", all.");
            return true;
        }
        Component title = Util.c("&#4ADE80&l✦ ANNOUNCEMENT ✦");
        Component sub = Util.c("&f" + msg);
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(6), Duration.ofMillis(800)));
        for (Player p : to) {
            p.showTitle(t);
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
            Util.tell(p, "&8&m                    &r\n&#4ADE80&l  ANNOUNCEMENT\n&f  " + msg + "\n&8&m                    ");
        }
        Util.tell(s, "&#A3E635[Announce] &7Sent to &f" + to.size() + " &7player" + (to.size() == 1 ? "" : "s") + ".");
        return true;
    }

    private List<String> worlds() {
        List<String> l = new ArrayList<>();
        for (var w : Bukkit.getWorlds()) l.add(w.getName());
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("mobzone.admin")) {
            out.add("all"); out.addAll(worlds());
            out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
