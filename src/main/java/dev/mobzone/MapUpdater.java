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
    List<String> update(String which, Player near, boolean apply) {
        List<String> out = new ArrayList<>();
        boolean touchedSpawners = false, touchedBoss = false;
        for (Zone z : plugin.zones().all()) {
            if (!which.equalsIgnoreCase("all") && !z.name.equalsIgnoreCase(which)) continue;
            World w = Bukkit.getWorld(z.world);
            if (w == null) { out.add("&c" + z.name + ": world " + z.world + " is not loaded."); continue; }
            SpotPicker.Cell start = findStart(w, z, near);
            if (start == null) { out.add("&c" + z.name + ": no floor found inside the zone. Is the map pasted, and are pos1/pos2 right?"); continue; }
            Set<SpotPicker.Cell> reach = SpotPicker.flood(grid(w, z), start, 60000);
            if (reach.size() < 50) { out.add("&c" + z.name + ": only " + reach.size() + " walkable blocks found, zone looks closed off."); continue; }
            boolean hasBoss = plugin.getConfig().isConfigurationSection("boss.bosses." + z.name);
            List<SpotPicker.Cell> avoid = new ArrayList<>();
            SpotPicker.Cell boss = null;
            if (hasBoss) {
                boss = SpotPicker.bossSpot(reach, (z.minX + z.maxX) / 2.0, (z.minZ + z.maxZ) / 2.0, 4);
                avoid.add(boss);
            }
            List<String> names = new ArrayList<>(z.spawners);
            List<SpotPicker.Cell> spots = SpotPicker.pick(reach, names.size(), avoid, z.name.hashCode(), 30, new int[]{16, 10, 6, 0});
            StringBuilder sb = new StringBuilder("&a" + z.name + "&7 (" + reach.size() + " walkable): ");
            for (int i = 0; i < names.size(); i++) {
                SpotPicker.Cell c = spots.get(i);
                sb.append(names.get(i)).append(' ').append(c.x()).append(' ').append(c.y()).append(' ').append(c.z()).append("  ");
                if (apply) {
                    if (writeSpawner(names.get(i), z.world, c.x(), c.y(), c.z())) touchedSpawners = true;
                    else out.add("&c  spawner file for '" + names.get(i) + "' not found in plugins/MythicMobs.");
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
            if (touchedSpawners) { Util.console("mm reload"); out.add("&aSpawner files updated and MythicMobs reloaded."); }
        } else out.add("&ePreview only. Run /mobzone update " + which + " to apply.");
        return out;
    }

    /** Move one spawner to a location (for /mobzone set spawner). */
    boolean setSpawner(String name, Location l) {
        boolean ok = writeSpawner(name, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (ok) Util.console("mm reload");
        return ok;
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

    private boolean writeSpawner(String name, String world, int px, int py, int pz) {
        File root = new File(plugin.getDataFolder().getParentFile(), "MythicMobs");
        if (!root.isDirectory()) return false;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root.toPath())) {
            s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
             .filter(p -> { for (Path part : p) if (part.toString().equalsIgnoreCase("spawners")) return true; return false; })
             .forEach(files::add);
        } catch (IOException e) { return false; }
        for (Path p : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(p.toFile());
            for (String key : cfg.getKeys(false)) {
                if (!key.equalsIgnoreCase(name)) continue;
                cfg.set(key + ".World", world);
                cfg.set(key + ".X", px);
                cfg.set(key + ".Y", py);
                cfg.set(key + ".Z", pz);
                try { cfg.save(p.toFile()); } catch (IOException e) { return false; }
                return true;
            }
        }
        return false;
    }
}
