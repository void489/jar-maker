package dev.mobzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MobZoneCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of(
            "pos1", "pos2", "create", "delete", "list", "info", "addspawner", "removespawner", "reload", "boss");

    private final MobZonePlugin plugin;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public MobZoneCommand(MobZonePlugin plugin) {
        this.plugin = plugin;
    }

    private static void say(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("mobzone.admin")) {
            say(sender, "You don't have permission.", NamedTextColor.RED);
            return true;
        }
        if (a.length == 0) {
            say(sender, "/mobzone <" + String.join("|", SUBS) + ">", NamedTextColor.YELLOW);
            return true;
        }
        ZoneManager zm = plugin.zones();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) {
                    say(sender, "Players only.", NamedTextColor.RED);
                    return true;
                }
                Location l = p.getLocation().getBlock().getLocation();
                (a[0].equalsIgnoreCase("pos1") ? pos1 : pos2).put(p.getUniqueId(), l);
                say(sender, a[0] + " set to " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(),
                        NamedTextColor.GREEN);
            }
            case "create" -> {
                if (!(sender instanceof Player p)) {
                    say(sender, "Players only.", NamedTextColor.RED);
                    return true;
                }
                if (a.length < 2) {
                    say(sender, "/mobzone create <name> [minY maxY]", NamedTextColor.YELLOW);
                    return true;
                }
                Location l1 = pos1.get(p.getUniqueId());
                Location l2 = pos2.get(p.getUniqueId());
                if (l1 == null || l2 == null) {
                    say(sender, "Set /mobzone pos1 and pos2 first.", NamedTextColor.RED);
                    return true;
                }
                if (l1.getWorld() == null || !l1.getWorld().equals(l2.getWorld())) {
                    say(sender, "Both positions must be in the same world.", NamedTextColor.RED);
                    return true;
                }
                int minY = Math.min(l1.getBlockY(), l2.getBlockY());
                int maxY = Math.max(l1.getBlockY(), l2.getBlockY()) + plugin.yExtendUp;
                if (a.length >= 4) {
                    try {
                        minY = Integer.parseInt(a[2]);
                        maxY = Integer.parseInt(a[3]);
                    } catch (NumberFormatException ex) {
                        say(sender, "minY and maxY must be whole numbers.", NamedTextColor.RED);
                        return true;
                    }
                }
                Zone z = new Zone(a[1], l1.getWorld().getName(),
                        l1.getBlockX(), minY, l1.getBlockZ(), l2.getBlockX(), maxY, l2.getBlockZ());
                if (!zm.add(z)) {
                    say(sender, "A zone named " + a[1] + " already exists.", NamedTextColor.RED);
                    return true;
                }
                say(sender, "Zone " + z.name + " created: x " + z.minX + ".." + z.maxX
                        + ", y " + z.minY + ".." + z.maxY + ", z " + z.minZ + ".." + z.maxZ,
                        NamedTextColor.GREEN);
            }
            case "delete" -> {
                if (a.length < 2) { say(sender, "/mobzone delete <name>", NamedTextColor.YELLOW); return true; }
                boolean removed = zm.remove(a[1]);
                say(sender, removed ? "Deleted " + a[1] : "No such zone.",
                        removed ? NamedTextColor.GREEN : NamedTextColor.RED);
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder();
                for (Zone z : zm.all()) {
                    sb.append(z.name).append(" (").append(z.spawners.size()).append(" spawners, ")
                            .append(plugin.trackedCount(z.name)).append(" mobs)  ");
                }
                say(sender, sb.isEmpty() ? "No zones." : sb.toString().trim(), NamedTextColor.YELLOW);
            }
            case "info" -> {
                if (a.length < 2) { say(sender, "/mobzone info <name>", NamedTextColor.YELLOW); return true; }
                Zone z = zm.get(a[1]);
                if (z == null) { say(sender, "No such zone.", NamedTextColor.RED); return true; }
                say(sender, z.name + " [" + z.world + "] x " + z.minX + ".." + z.maxX
                        + " y " + z.minY + ".." + z.maxY + " z " + z.minZ + ".." + z.maxZ,
                        NamedTextColor.YELLOW);
                say(sender, "Spawners: " + (z.spawners.isEmpty() ? "none" : String.join(", ", z.spawners)),
                        NamedTextColor.YELLOW);
                say(sender, "Tracked mobs: " + plugin.trackedCount(z.name), NamedTextColor.YELLOW);
            }
            case "addspawner" -> {
                if (a.length < 3) { say(sender, "/mobzone addspawner <zone> <spawner>", NamedTextColor.YELLOW); return true; }
                Zone z = zm.get(a[1]);
                if (z == null) { say(sender, "No such zone.", NamedTextColor.RED); return true; }
                Zone other = zm.bySpawner(a[2]);
                if (other != null && other != z) {
                    say(sender, a[2] + " already belongs to zone " + other.name + ".", NamedTextColor.RED);
                    return true;
                }
                z.spawners.add(a[2].toLowerCase(Locale.ROOT));
                zm.changed();
                say(sender, "Added " + a[2] + " to " + z.name + ". Mobs already spawned are not affected until they respawn.",
                        NamedTextColor.GREEN);
            }
            case "removespawner" -> {
                if (a.length < 3) { say(sender, "/mobzone removespawner <zone> <spawner>", NamedTextColor.YELLOW); return true; }
                Zone z = zm.get(a[1]);
                if (z == null) { say(sender, "No such zone.", NamedTextColor.RED); return true; }
                if (z.spawners.remove(a[2].toLowerCase(Locale.ROOT))) {
                    zm.changed();
                    say(sender, "Removed " + a[2] + " from " + z.name, NamedTextColor.GREEN);
                } else {
                    say(sender, a[2] + " is not in " + z.name, NamedTextColor.RED);
                }
            }
            case "boss" -> {
                BossManager bm = plugin.boss();
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
                switch (sub) {
                    case "spawn" -> {
                        String err = bm.spawnNow(a.length > 2 ? a[2] : null);
                        say(sender, err == null ? "Boss spawned." : err,
                                err == null ? NamedTextColor.GREEN : NamedTextColor.RED);
                    }
                    case "despawn" -> say(sender, bm.despawnNow() ? "Boss removed." : "No boss is active.",
                            NamedTextColor.YELLOW);
                    case "reset" -> {
                        bm.resetTimer();
                        say(sender, "Boss timer restarted with a new random zone.", NamedTextColor.GREEN);
                    }
                    case "status" -> {
                        for (String line : bm.status()) say(sender, line, NamedTextColor.YELLOW);
                    }
                    default -> say(sender, "/mobzone boss <spawn [zone|random]|despawn|status|reset>",
                            NamedTextColor.YELLOW);
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                say(sender, "MobZone reloaded.", NamedTextColor.GREEN);
            }
            default -> say(sender, "Unknown subcommand.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        if (!sender.hasPermission("mobzone.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String s : SUBS) if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("boss")) {
            if (a.length == 2) {
                for (String s : List.of("spawn", "despawn", "status", "reset"))
                    if (s.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(s);
            } else if (a.length == 3 && a[1].equalsIgnoreCase("spawn")) {
                List<String> opts = new ArrayList<>(plugin.boss().zoneKeys());
                opts.add("random");
                for (String s : opts) if (s.startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (a.length == 2 && List.of("delete", "info", "addspawner", "removespawner").contains(sub)) {
            for (Zone z : plugin.zones().all())
                if (z.name.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(z.name);
        } else if (a.length == 3 && sub.equals("removespawner")) {
            Zone z = plugin.zones().get(a[1]);
            if (z != null) for (String s : z.spawners) if (s.startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
