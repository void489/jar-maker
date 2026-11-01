package dev.mobzone;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class MobZonePlugin extends JavaPlugin {
    public record Tracked(Mob mob, String zone) {}

    public NamespacedKey zoneKey;
    private final ZoneManager zones = new ZoneManager(this);
    private final BossManager boss = new BossManager(this);
    /** Only mobs that came from a configured spawner are in here. Main thread only. */
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private BukkitTask task;

    public double edgeMargin = 0.4;
    public double knockbackGuard = 4.0;
    public double edgeBand = 1.5;
    public int intervalTicks = 4;
    public int yExtendUp = 6;
    public boolean snapOnBreach = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        zoneKey = new NamespacedKey(this, "zone");
        reloadAll();

        getServer().getPluginManager().registerEvents(new MobZoneListener(this), this);
        getServer().getPluginManager().registerEvents(boss, this);
        MobZoneCommand cmd = new MobZoneCommand(this);
        var pc = getCommand("mobzone");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        boss.shutdown();
        tracked.clear();
    }

    public void reloadAll() {
        reloadConfig();
        var c = getConfig();
        edgeMargin = c.getDouble("settings.edge-margin", 0.4);
        knockbackGuard = Math.max(0.1, c.getDouble("settings.knockback-guard", 4.0));
        edgeBand = c.getDouble("settings.edge-band", 1.5);
        intervalTicks = Math.max(1, c.getInt("settings.check-interval-ticks", 4));
        yExtendUp = c.getInt("settings.y-extend-up", 6);
        snapOnBreach = c.getBoolean("settings.snap-on-breach", true);
        zones.load(c);

        if (task != null) task.cancel();
        task = getServer().getScheduler().runTaskTimer(this, this::sweep, intervalTicks, intervalTicks);
        boss.reload();
    }

    public ZoneManager zones() { return zones; }

    public BossManager boss() { return boss; }

    public void track(Mob mob, String zoneName) {
        tracked.put(mob.getUniqueId(), new Tracked(mob, zoneName));
    }

    public Zone zoneOf(org.bukkit.entity.Entity e) {
        Tracked t = tracked.get(e.getUniqueId());
        return t == null ? null : zones.get(t.zone());
    }

    public int trackedCount(String zoneName) {
        int n = 0;
        for (Tracked t : tracked.values()) if (t.zone().equalsIgnoreCase(zoneName)) n++;
        return n;
    }

    /** Re-track a persisted mob (after a chunk load) from its saved zone tag. */
    public void retrack(org.bukkit.entity.Entity e) {
        if (!(e instanceof Mob mob)) return;
        String z = mob.getPersistentDataContainer().get(zoneKey, PersistentDataType.STRING);
        if (z != null && zones.get(z) != null) track(mob, zones.get(z).name);
    }

    /** Runs every few ticks over tracked mobs only. */
    private void sweep() {
        Iterator<Tracked> it = tracked.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            Mob mob = t.mob();
            Zone z = zones.get(t.zone());
            if (z == null || !mob.isValid()) { // dead, despawned, unloaded, or zone deleted
                it.remove();
                continue;
            }
            World w = mob.getWorld();
            if (!w.getName().equals(z.world)) continue;

            double x = mob.getX(), y = mob.getY(), zz = mob.getZ();

            if (!z.contains(x, y, zz, edgeMargin)) {
                if (snapOnBreach) snapBack(mob, z, w, x, y, zz);
                continue;
            }

            // Stop chasing a target that left the zone.
            LivingEntity target = mob.getTarget();
            if (target != null && !z.contains(target.getX(), target.getY(), target.getZ(), 0)) {
                mob.setTarget(null);
            }

            // Near a wall: drop any path whose next point is outside.
            if (z.nearEdge(x, zz, edgeMargin, edgeBand)) {
                Pathfinder pf = mob.getPathfinder();
                if (pf.hasPath()) {
                    var path = pf.getCurrentPath();
                    if (path != null) {
                        Location next = path.getNextPoint();
                        if (next != null && !z.contains(next.getX(), next.getY(), next.getZ(), edgeMargin)) {
                            pf.stopPathfinding();
                        }
                    }
                }
            }
        }
    }

    private void snapBack(Mob mob, Zone z, World w, double x, double y, double zz) {
        Location cur = mob.getLocation();
        Location dest = new Location(w,
                z.clampX(x, edgeMargin), z.clampY(y), z.clampZ(zz, edgeMargin),
                cur.getYaw(), cur.getPitch());
        mob.setVelocity(new Vector(0, 0, 0));
        mob.getPathfinder().stopPathfinding();
        mob.setTarget(null);
        mob.teleport(dest);
    }
}
