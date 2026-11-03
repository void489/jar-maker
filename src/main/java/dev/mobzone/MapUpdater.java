package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Re-places every zone's spawners and boss spot automatically after a map change:
 * walks the zone on foot (flood fill), then picks open, spread-out floor spots that zombies can really reach.
 */
final class MapUpdater {
    private final MobZonePlugin plugin;

    MapUpdater(MobZonePlugin plugin) { this.plugin = plugin; }

    private static boolean clearBlock(Block b) {
        return b.isPassable() || b.getType().name().endsWith("_CARPET");
    }

    private static boolean liquid(Block b) { return b.isLiquid(); }

    private Grid grid(World w, Zone z) { return new Grid(w, z); }

    private static final class Grid implements SpotPicker.Grid {
        private final World w; private final Zone z;
        Grid(World w, Zone z) { this.w = w; this.z = z; }
        public boolean inside(int x, int y, int zz) { return x >= z.minX && x <= z.maxX && zz >= z.minZ && zz <= z.maxZ && y >= z.minY && y <= z.maxY; }
        public boolean clear(int x, int y, int zz) { Block b = w.getBlockAt(x, y, zz); return clearBlock(b) && !liquid(b); }
        public boolean standable(int x, int y, int zz) {
            Block feet = w.getBlockAt(x, y, zz), head = w.getBlockAt(x, y + 1, zz), below = w.getBlockAt(x, y - 1, zz);
            if (!clearBlock(feet) || liquid(feet) || !clearBlock(head) || liquid(head)) return false;
            return below.getType().isSolid() && !liquid(below);
        }
    }

    private SpotPicker.Cell findStart(World w, Zone z, Player near) {
        Grid g = grid(w, z);
        if (near != null && near.getWorld().equals(w)) {
            Location l = near.getLocation();
            SpotPicker.Cell c = new SpotPicker.Cell(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (g.inside(c.x(), c.y(), c.z()) && g.standable(c.x(), c.y(), c.z())) return c;
        }
        int cx = (z.minX + z.maxX) / 2, cz = (z.minZ + z.maxZ) / 2;
        SpotPicker.Cell best = null; int bestSize = 0;
        List<Set<SpotPicker.Cell>> seen = new ArrayList<>();
        for (int r = 0; r <= 30; r++) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int y = z.maxY; y >= z.minY; y--) {
                    if (!g.standable(cx + dx, y, cz + dz)) continue;
                    SpotPicker.Cell c = new SpotPicker.Cell(cx + dx, y, cz + dz);
                    boolean known = false;
                    for (Set<SpotPicker.Cell> s : seen) if (s.contains(c)) { known = true; break; }
                    if (known) break;
                    Set<SpotPicker.Cell> s = SpotPicker.flood(g, c, 60000);
                    seen.add(s);
                    if (s.size() > bestSize) { bestSize = s.size(); best = c; }
                    if (seen.size() >= 4) return best;
                    break;
                }
            }
        }
        return best;
    }

    /** which = zone name or "all". Returns lines for the sender. */
    List<String> update(String which, Player near, boolean apply, Integer forceY) {
        List<String> out = new ArrayList<>();
        boolean touchedSpawners = false, touchedBoss = false;
        for (Zone z : plugin.zones().all()) {
            if (!which.equalsIgnoreCase("all") && !z.name.equalsIgnoreCase(which)) continue;
            World w = Bukkit.getWorld(z.world);
            if (w == null) { out.add("&c" + z.name + ": world " + z.world + " is not loaded."); continue; }
            SpotPicker.Cell start = findStart(w, z, near);
            if (start == null) { out.add("&c" + z.name + ": no floor found inside the zone. Is the map pasted, and are pos1/pos2 right?"); continue; }
            Set<SpotPicker.Cell> reach = SpotPicker.flood(grid(w, z), start, 60000);
            int walkable = reach.size();
            if (reach.size() < 50) { out.add("&c" + z.name + ": only " + reach.size() + " walkable blocks found, zone looks closed off."); continue; }
            // ground floor only (most common floor height +-1), and real air at feet, head and above
            java.util.Map<Integer, Integer> perY = new java.util.HashMap<>();
            for (SpotPicker.Cell c : reach) perY.merge(c.y(), 1, Integer::sum);
            int groundY = reach.iterator().next().y(), most = 0;
            for (var e : perY.entrySet()) if (e.getValue() > most) { most = e.getValue(); groundY = e.getKey(); }
            if (forceY != null) groundY = forceY;
            Set<SpotPicker.Cell> ground = new java.util.HashSet<>();
            for (SpotPicker.Cell c : reach) {
                if (Math.abs(c.y() - groundY) > 1) continue;
                if (w.getBlockAt(c.x(), c.y(), c.z()).getType().isAir() && w.getBlockAt(c.x(), c.y() + 1, c.z()).getType().isAir()
                        && w.getBlockAt(c.x(), c.y() + 2, c.z()).getType().isAir()) ground.add(c);
            }
            if (forceY != null && ground.size() < 20) { out.add("&c" + z.name + ": almost no open floor at y=" + forceY + " (found " + ground.size() + "). Check the zone pos1/pos2 and that the map is pasted."); continue; }
            if (ground.size() < 50) { out.add("&e" + z.name + ": little open ground floor found, using all walkable spots."); ground = reach; }
            reach = ground;
            boolean hasBoss = plugin.getConfig().isConfigurationSection("boss.bosses." + z.name);
            List<SpotPicker.Cell> avoid = new ArrayList<>();
            SpotPicker.Cell boss = null;
            if (hasBoss) {
                boss = SpotPicker.bossSpot(reach, (z.minX + z.maxX) / 2.0, (z.minZ + z.maxZ) / 2.0, 4);
                avoid.add(boss);
            }
            List<String> names = new ArrayList<>(z.spawners);
            List<SpotPicker.Cell> spots = SpotPicker.pick(reach, names.size(), avoid, z.name.hashCode(), 30, new int[]{16, 10, 6, 0});
            StringBuilder sb = new StringBuilder("&a" + z.name + "&7 (" + reach.size() + " ground spots): ");
            for (int i = 0; i < names.size(); i++) {
                SpotPicker.Cell c = spots.get(i);
                sb.append(names.get(i)).append(' ').append(c.x()).append(' ').append(c.y()).append(' ').append(c.z()).append("  ");
                if (apply) {
                    String err = moveSpawner(names.get(i), z.world, c.x(), c.y(), c.z());
                    if (err == null) touchedSpawners = true; else out.add("&c  " + names.get(i) + ": " + err);
                }
            }
            if (boss != null) {
                sb.append("boss ").append(boss.x()).append(' ').append(boss.y()).append(' ').append(boss.z());
                if (apply) {
                    plugin.getConfig().set("boss.bosses." + z.name + ".x", boss.x() + 0.5);
                    plugin.getConfig().set("boss.bosses." + z.name + ".y", (double) boss.y());
                    plugin.getConfig().set("boss.bosses." + z.name + ".z", boss.z() + 0.5);
                    touchedBoss = true;
                }
            }
            out.add(sb.toString());
        }
        if (out.isEmpty()) out.add("&cNo zone matches '" + which + "'. Use /mobzone list.");
        if (apply) {
            if (touchedBoss) { plugin.saveConfig(); plugin.boss().reload(); }
            if (touchedSpawners) out.add("&aSpawners moved inside MythicMobs (no reload needed).");
        } else out.add("&ePreview only. Run /mobzone update " + which + " to apply.");
        return out;
    }

    /** Move one spawner to a location (for /mobzone set spawner). */
    boolean setSpawner(String name, Location l) {
        return moveSpawner(name, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()) == null;
    }

    boolean setBoss(String zone, Location l) {
        if (!plugin.getConfig().isConfigurationSection("boss.bosses." + zone)) return false;
        plugin.getConfig().set("boss.bosses." + zone + ".x", l.getBlockX() + 0.5);
        plugin.getConfig().set("boss.bosses." + zone + ".y", (double) l.getBlockY());
        plugin.getConfig().set("boss.bosses." + zone + ".z", l.getBlockZ() + 0.5);
        plugin.saveConfig();
        plugin.boss().reload();
        return true;
    }

    /** Moves a spawner through the MythicMobs API (so MythicMobs saves it itself). Returns null on success, else an error text. */
    private String moveSpawner(String name, String world, int px, int py, int pz) {
        try {
            Object mm = io.lumine.mythic.bukkit.MythicBukkit.inst();
            Object mgr = mm.getClass().getMethod("getSpawnerManager").invoke(mm);
            Object sp = mgr.getClass().getMethod("getSpawnerByName", String.class).invoke(mgr, name);
            if (sp == null) return "spawner not found in MythicMobs";
            Location loc = new Location(Bukkit.getWorld(world), px + 0.5, py, pz + 0.5);
            Object abs = io.lumine.mythic.bukkit.BukkitAdapter.adapt(loc);
            invoke1(sp, "setWorld", world);
            invoke1(sp, "setLocation", abs);
            try { sp.getClass().getMethod("reattachToWorld").invoke(sp); } catch (NoSuchMethodException ignored) { }
            try { sp.getClass().getMethod("saveSpawnerData").invoke(sp); } catch (NoSuchMethodException ignored) { }
            return null;
        } catch (Exception e) {
            return "could not move it (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    private static void invoke1(Object target, String method, Object arg) throws Exception {
        for (java.lang.reflect.Method m : target.getClass().getMethods())
            if (m.getName().equals(method) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) {
                m.invoke(target, arg);
                return;
            }
        throw new NoSuchMethodException(method);
    }
}
