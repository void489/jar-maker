package dev.mobzone;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Loads, saves and indexes zones. Zone and spawner lookups are case-insensitive. */
public final class ZoneManager {
    private final MobZonePlugin plugin;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<String, Zone> bySpawner = new LinkedHashMap<>();

    public ZoneManager(MobZonePlugin plugin) {
        this.plugin = plugin;
    }

    public void load(FileConfiguration cfg) {
        zones.clear();
        ConfigurationSection root = cfg.getConfigurationSection("zones");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                String world = s.getString("world");
                ConfigurationSection a = s.getConfigurationSection("pos1");
                ConfigurationSection b = s.getConfigurationSection("pos2");
                if (world == null || a == null || b == null) {
                    plugin.getLogger().warning("Zone '" + key + "' is missing world/pos1/pos2 - skipped.");
                    continue;
                }
                Zone z = new Zone(key, world,
                        a.getInt("x"), a.getInt("y"), a.getInt("z"),
                        b.getInt("x"), b.getInt("y"), b.getInt("z"));
                for (String sp : s.getStringList("spawners")) {
                    z.spawners.add(sp.toLowerCase(Locale.ROOT));
                }
                zones.put(key.toLowerCase(Locale.ROOT), z);
            }
        }
        reindex();
    }

    public void save() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("zones", null);
        for (Zone z : zones.values()) {
            String base = "zones." + z.name;
            cfg.set(base + ".world", z.world);
            cfg.set(base + ".pos1.x", z.minX);
            cfg.set(base + ".pos1.y", z.minY);
            cfg.set(base + ".pos1.z", z.minZ);
            cfg.set(base + ".pos2.x", z.maxX);
            cfg.set(base + ".pos2.y", z.maxY);
            cfg.set(base + ".pos2.z", z.maxZ);
            cfg.set(base + ".spawners", new ArrayList<>(z.spawners));
        }
        plugin.saveConfig();
    }

    private void reindex() {
        bySpawner.clear();
        for (Zone z : zones.values()) {
            for (String sp : z.spawners) {
                Zone prev = bySpawner.putIfAbsent(sp, z);
                if (prev != null && prev != z) {
                    plugin.getLogger().warning("Spawner '" + sp + "' is in both '" + prev.name
                            + "' and '" + z.name + "'. Using '" + prev.name + "'.");
                }
            }
        }
    }

    public Zone get(String name) {
        return name == null ? null : zones.get(name.toLowerCase(Locale.ROOT));
    }

    public Zone bySpawner(String spawnerName) {
        return spawnerName == null ? null : bySpawner.get(spawnerName.toLowerCase(Locale.ROOT));
    }

    public Iterable<Zone> all() { return zones.values(); }

    public boolean add(Zone z) {
        String k = z.name.toLowerCase(Locale.ROOT);
        if (zones.containsKey(k)) return false;
        zones.put(k, z);
        reindex();
        save();
        return true;
    }

    public boolean remove(String name) {
        Zone z = zones.remove(name.toLowerCase(Locale.ROOT));
        if (z == null) return false;
        reindex();
        save();
        return true;
    }

    /** Call after changing a zone's spawner set. */
    public void changed() {
        reindex();
        save();
    }
}
