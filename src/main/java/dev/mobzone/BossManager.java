package dev.mobzone;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One boss at a time, on a timer, from a random zone. Group fight: the cash reward is split by damage.
 * The boss is tagged and tracked like a spawner mob, so the zone boundary still applies to it.
 */
public final class BossManager implements Listener {
    private record BossDef(String zone, String mob, String name, double x, double y, double z, long reward, long tokens) {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final MobZonePlugin plugin;
    private final Map<String, BossDef> defs = new LinkedHashMap<>();
    private BukkitTask task;

    private boolean enabled;
    private long intervalMs;
    private long despawnMs;
    private double minShare;
    private int minPlayers;
    private List<Integer> warnings = List.of(300, 60, 10);

    // schedule
    private long nextSpawnAt;
    private BossDef nextDef;
    private final Set<Integer> announced = new HashSet<>();
    private String lastZone;

    // active boss
    private Mob activeMob;
    private UUID activeId;
    private BossDef activeDef;
    private long activeSince;
    private double maxHealth;
    private final Map<UUID, Double> damage = new HashMap<>();

    public BossManager(MobZonePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("boss.enabled", true);
        intervalMs = Math.max(1, c.getLong("boss.interval-minutes", 60)) * 60_000L;
        despawnMs = Math.max(1, c.getLong("boss.despawn-minutes", 30)) * 60_000L;
        minShare = Math.max(0, c.getDouble("boss.min-damage-share", 0.05));
        minPlayers = Math.max(0, c.getInt("boss.min-players", 1));
        List<Integer> w = new ArrayList<>(c.getIntegerList("boss.warnings-seconds"));
        warnings = w.isEmpty() ? List.of(300, 60, 10) : w;

        defs.clear();
        ConfigurationSection root = c.getConfigurationSection("boss.bosses");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null || s.getString("mob") == null) continue;
                defs.put(key.toLowerCase(Locale.ROOT), new BossDef(
                        key.toLowerCase(Locale.ROOT),
                        s.getString("mob"),
                        s.getString("name", key),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        s.getLong("reward", 0),
                        s.getLong("tokens", defaultTokens(key.toLowerCase(Locale.ROOT)))));
            }
        }

        if (task != null) task.cancel();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        if (nextDef == null || !defs.containsKey(nextDef.zone())) schedule();
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    public List<String> zoneKeys() {
        return new ArrayList<>(defs.keySet());
    }

    private void schedule() {
        nextSpawnAt = System.currentTimeMillis() + intervalMs;
        nextDef = pick();
        announced.clear();
    }

    private BossDef pick() {
        if (defs.isEmpty()) return null;
        List<BossDef> pool = new ArrayList<>(defs.values());
        if (pool.size() > 1 && lastZone != null) pool.removeIf(d -> d.zone().equals(lastZone));
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private int playersOnline() {
        Set<String> worlds = new HashSet<>();
        for (BossDef d : defs.values()) {
            Zone z = plugin.zones().get(d.zone());
            if (z != null) worlds.add(z.world);
        }
        int n = 0;
        for (String name : worlds) {
            World w = Bukkit.getWorld(name);
            if (w != null) n += w.getPlayers().size();
        }
        return n;
    }

    private static String pretty(String zone) {
        return zone.isEmpty() ? zone : Character.toUpperCase(zone.charAt(0)) + zone.substring(1);
    }

    private void say(String legacy) {
        Bukkit.broadcast(LEGACY.deserialize(legacy));
    }

    /** Runs once a second. Cheap: a few comparisons, no entity scans. */
    private void tick() {
        if (!enabled || defs.isEmpty()) return;
        long now = System.currentTimeMillis();

        if (activeMob != null) {
            if (!activeMob.isValid()) {
                say("&c[Boss] &7The boss has vanished.");
                clearActive();
            } else if (now - activeSince >= despawnMs) {
                say("&c[Boss] &f" + activeDef.name() + " &7has retreated. Nobody defeated it in time.");
                activeMob.remove();
                clearActive();
            }
        }

        if (nextDef == null) schedule();
        if (nextDef == null) return;

        long remaining = nextSpawnAt - now;
        if (remaining > 0) {
            int secs = (int) (remaining / 1000);
            Integer hit = null;
            for (int w : warnings) if (secs <= w && (hit == null || w < hit)) hit = w;
            if (hit != null && announced.add(hit)) {
                for (int w : warnings) if (w > hit) announced.add(w);
                if (activeMob == null && playersOnline() >= minPlayers) {
                    say("&c[Boss] &7A boss will appear in the &e" + pretty(nextDef.zone())
                            + " &7in &f" + formatSeconds(hit) + "&7!");
                }
            }
            return;
        }

        // Due. Wait while a boss is alive or nobody is in the world.
        if (activeMob != null || playersOnline() < minPlayers) {
            nextSpawnAt = now + 30_000L;
            return;
        }
        BossDef def = nextDef;
        String err = spawn(def);
        lastZone = def.zone();
        schedule();
        if (err != null) plugin.getLogger().warning("Scheduled boss failed: " + err);
    }

    private static String formatSeconds(int s) {
        if (s >= 60 && s % 60 == 0) return (s / 60) + (s == 60 ? " minute" : " minutes");
        return s + " seconds";
    }

    /** Returns an error message, or null on success. */
    private String spawn(BossDef def) {
        if (activeMob != null) return "A boss is already active (" + pretty(activeDef.zone()) + "). Despawn it first.";

        Zone zone = plugin.zones().get(def.zone());
        String worldName = zone != null ? zone.world : plugin.getConfig().getString("boss.world", "prison");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return "World '" + worldName + "' is not loaded.";

        Location loc = new Location(world, def.x(), def.y(), def.z());
        loc.getChunk().load();

        var mm = MythicBukkit.inst().getMobManager().getMythicMob(def.mob()).orElse(null);
        if (mm == null) return "MythicMobs has no mob named " + def.mob() + ".";
        var am = mm.spawn(BukkitAdapter.adapt(loc), 1);
        if (am == null) return "MythicMobs could not spawn " + def.mob() + ".";
        Entity ent = am.getEntity().getBukkitEntity();
        if (!(ent instanceof Mob mob)) {
            ent.remove();
            return def.mob() + " is not a mob type that can be tracked.";
        }

        if (zone != null) {
            mob.getPersistentDataContainer().set(plugin.zoneKey, PersistentDataType.STRING, zone.name);
            plugin.track(mob, zone.name);
        }
        activeMob = mob;
        activeId = mob.getUniqueId();
        activeDef = def;
        activeSince = System.currentTimeMillis();
        maxHealth = 0;
        damage.clear();

        say("&c[Boss] &f" + def.name() + " &7has appeared in the &e" + pretty(def.zone())
                + "&7! Defeat it within " + (despawnMs / 60_000L) + " minutes.");
        return null;
    }

    private void clearActive() {
        activeMob = null;
        activeId = null;
        activeDef = null;
        damage.clear();
        maxHealth = 0;
    }

    // ---- admin ----

    public String spawnNow(String zoneKey) {
        if (defs.isEmpty()) return "No bosses are configured under boss.bosses in config.yml.";
        BossDef def;
        if (zoneKey == null || zoneKey.equalsIgnoreCase("random")) {
            def = pick();
        } else {
            def = defs.get(zoneKey.toLowerCase(Locale.ROOT));
            if (def == null) return "No boss configured for zone '" + zoneKey + "'.";
        }
        return spawn(def);
    }

    public boolean despawnNow() {
        if (activeMob == null) return false;
        say("&c[Boss] &f" + activeDef.name() + " &7was removed by an admin.");
        activeMob.remove();
        clearActive();
        return true;
    }

    public void resetTimer() {
        schedule();
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        if (!enabled) out.add("Boss events are disabled in config.yml.");
        if (activeMob != null) {
            long left = Math.max(0, despawnMs - (System.currentTimeMillis() - activeSince)) / 1000;
            out.add("Active: " + activeDef.name() + " in " + pretty(activeDef.zone())
                    + ", retreats in " + (left / 60) + "m " + (left % 60) + "s. Fighters: " + damage.size());
        } else {
            out.add("No boss is active.");
        }
        if (nextDef != null) {
            long left = Math.max(0, nextSpawnAt - System.currentTimeMillis()) / 1000;
            out.add("Next: " + pretty(nextDef.zone()) + " in " + (left / 60) + "m " + (left % 60) + "s"
                    + " (needs " + minPlayers + " player(s) in the prison world).");
        }
        return out;
    }

    // ---- fight tracking ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (activeMob == null || !e.getEntity().getUniqueId().equals(activeId)) return;

        Player p = null;
        Entity d = e.getDamager();
        if (d instanceof Player direct) {
            p = direct;
        } else if (d instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            p = shooter;
        }
        if (p == null) return;

        double hp = activeMob.getHealth(); // health before this hit is applied
        maxHealth = Math.max(maxHealth, hp);
        double dealt = Math.min(e.getFinalDamage(), hp);
        if (dealt > 0) damage.merge(p.getUniqueId(), dealt, Double::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (activeMob == null || !e.getEntity().getUniqueId().equals(activeId)) return;
        payout();
        clearActive();
    }

    private static long defaultTokens(String zone) {
        return switch (zone) {
            case "cellblock" -> 5; case "cafeteria" -> 8; case "laundry" -> 12;
            case "infirmary" -> 18; case "yard" -> 25; case "sewers" -> 35;
            case "lab" -> 50; case "quarantine" -> 70; case "subbasement" -> 100;
            default -> 5;
        };
    }

    private void payout() {
        BossDef def = activeDef;
        Map<UUID, Long> shares = BossMath.split(damage, minShare * maxHealth, def.reward());
        if (shares.isEmpty()) {
            say("&c[Boss] &f" + def.name() + " &7was defeated, but nobody earned a reward.");
            return;
        }
        Map<UUID, Long> tokenShares = def.tokens() > 0
                ? BossMath.split(damage, minShare * maxHealth, def.tokens()) : Map.of();
        double sum = 0;
        for (UUID id : shares.keySet()) sum += damage.getOrDefault(id, 0.0);

        say("&c[Boss] &f" + def.name() + " &7was defeated! Rewards split by damage:");
        int rank = 1;
        for (Map.Entry<UUID, Long> en : shares.entrySet()) {
            Player online = Bukkit.getPlayer(en.getKey());
            String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(en.getKey()).getName();
            if (name == null) continue;
            long amount = en.getValue();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + name + " " + amount);
            long tok = tokenShares.getOrDefault(en.getKey(), 0L);
            if (tok > 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points give " + name + " " + tok);
            if (rank <= 10) {
                int pct = (int) Math.round(100.0 * damage.getOrDefault(en.getKey(), 0.0) / sum);
                say("&7 " + rank + ". &f" + name + " &7- " + pct + "% - &a$" + String.format("%,d", amount)
                        + (tok > 0 ? " &7+ &b" + tok + " Bio Tokens" : ""));
            }
            rank++;
        }
    }
}
