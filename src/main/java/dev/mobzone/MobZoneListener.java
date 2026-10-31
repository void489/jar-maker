package dev.mobzone;

import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.spawning.spawners.MythicSpawner;
import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class MobZoneListener implements Listener {
    private final MobZonePlugin plugin;

    public MobZoneListener(MobZonePlugin plugin) {
        this.plugin = plugin;
    }

    /** Tag and track mobs that came from a configured MythicMobs spawner. Nothing else is touched. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMythicSpawn(MythicMobSpawnEvent e) {
        if (!Boolean.TRUE.equals(e.isFromMythicSpawner())) return;
        MythicSpawner sp = e.getMythicSpawner();
        if (sp == null) return;

        Zone zone = plugin.zones().bySpawner(sp.getName());
        if (zone == null) zone = plugin.zones().bySpawner(sp.getInternalName());
        if (zone == null) return;

        Entity ent = e.getEntity();
        if (!(ent instanceof Mob mob)) return;

        mob.getPersistentDataContainer().set(plugin.zoneKey, PersistentDataType.STRING, zone.name);
        plugin.track(mob, zone.name);
    }

    /** Restart / chunk-load safety: re-attach tagged mobs. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) plugin.retrack(ent);
    }

    /**
     * Fires when a mob decides to start moving toward a location or entity. Covers chasing and
     * random strolling. Cancelling stops the move before it starts, so nothing jitters at the wall.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPathfind(EntityPathfindEvent e) {
        Zone z = plugin.zoneOf(e.getEntity());
        if (z == null) return;

        Location l = e.getLoc();
        if (l.getWorld() == null || !l.getWorld().getName().equals(z.world)
                || !z.contains(l.getX(), l.getY(), l.getZ(), plugin.edgeMargin)) {
            e.setCancelled(true);
            return;
        }
        Entity t = e.getTargetEntity();
        if (t != null && !z.contains(t.getX(), t.getY(), t.getZ(), 0)) {
            e.setCancelled(true);
        }
    }

    /** A mob never picks a target (a player or anything else) that is outside its zone. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Entity t = e.getTarget();
        if (t == null) return;
        Zone z = plugin.zoneOf(e.getEntity());
        if (z == null) return;
        if (!z.contains(t.getX(), t.getY(), t.getZ(), 0)) e.setCancelled(true);
    }

    /**
     * Scales knockback down smoothly as the mob nears a wall it is being pushed toward.
     * EntityPushedByEntityAttackEvent and EntityKnockbackByEntityEvent are subclasses and share this handler.
     */
    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        Zone z = plugin.zoneOf(e.getEntity());
        if (z == null) return;

        Vector k = e.getKnockback().clone();
        double m = plugin.edgeMargin;
        double g = plugin.knockbackGuard;
        double x = e.getEntity().getX();
        double zz = e.getEntity().getZ();

        k.setX(k.getX() * Zone.guardScale(x, k.getX(), z.loX(m), z.hiX(m), g));
        k.setZ(k.getZ() * Zone.guardScale(zz, k.getZ(), z.loZ(m), z.hiZ(m), g));
        e.setKnockback(k);
    }
}
