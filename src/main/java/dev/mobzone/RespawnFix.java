package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.Locale;

/**
 * Dying in a game mode respawns you at that mode's own warp instead of the lobby spawn.
 * Config (in-code default: prison -> starter):   respawn: { prison: starter }
 * Reads the Essentials warp file, so it uses the exact spot you set with /setwarp.
 * Also: hides death messages (hide-death-messages, default true) and sends oneblock players back to their island
 * after dying (oneblock-respawn-command, default "ob go"; set it to '' to turn that off).
 */
public final class RespawnFix implements Listener {
    private final MobZonePlugin plugin;

    public RespawnFix(MobZonePlugin plugin) { this.plugin = plugin; }

    private String warpFor(String world) {
        var sec = plugin.getConfig().getConfigurationSection("respawn");
        if (sec != null) {
            for (String k : sec.getKeys(false)) if (k.equalsIgnoreCase(world)) return sec.getString(k);
            return null;
        }
        return world.equalsIgnoreCase("prison") ? "starter" : null;
    }

    private Location warpLocation(String warp) {
        File f = new File(plugin.getDataFolder().getParentFile(), "Essentials/warps/" + warp.toLowerCase(Locale.ROOT) + ".yml");
        if (!f.isFile()) return null;
        var y = YamlConfiguration.loadConfiguration(f);
        String wn = y.getString("world-name", null);
        if (wn == null) return null;
        var w = Bukkit.getWorld(wn);
        if (w == null) return null;
        return new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
    }

    /** /spawn inside a oneblock world goes to the oneblock warp instead of the lobby. Config: spawn-redirect.oneblock: oneblock (world prefix: warp) */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawnCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("/")) raw = raw.substring(1);
        String cmd = raw.split("\\s+")[0];
        if (!(cmd.equals("spawn") || cmd.equals("espawn") || cmd.equals("essentials:spawn") || cmd.equals("essentialsspawn:spawn"))) return;
        var p = e.getPlayer();
        String world = p.getWorld().getName().toLowerCase(Locale.ROOT);
        var sec = plugin.getConfig().getConfigurationSection("spawn-redirect");
        String warp = null;
        if (sec != null) {
            for (String k : sec.getKeys(false)) if (world.startsWith(k.toLowerCase(Locale.ROOT))) { warp = sec.getString(k); break; }
        } else if (world.startsWith("oneblock")) warp = "oneblock";
        if (warp == null || warp.isBlank()) { plugin.getLogger().info("/spawn by " + p.getName() + " in world '" + p.getWorld().getName() + "': no spawn-redirect matches this world, leaving it to Essentials"); return; }
        e.setCancelled(true);
        plugin.getLogger().info("/spawn by " + p.getName() + " in world '" + p.getWorld().getName() + "' -> warp " + warp);
        Util.console("warp " + warp + " " + p.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("hide-death-messages", true)) e.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        var p = e.getPlayer();
        if (p.getWorld().getName().toLowerCase(Locale.ROOT).startsWith("oneblock")) {
            String cmd = plugin.getConfig().getString("oneblock-respawn-command", "ob go");
            if (cmd != null && !cmd.isBlank()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.performCommand(cmd); }, 5L);
                return;
            }
        }
        String warp = warpFor(p.getWorld().getName());
        if (warp == null || warp.isBlank()) return;
        Location loc = warpLocation(warp);
        if (loc != null) {
            e.setRespawnLocation(loc);
            return;
        }
        // fallback: let Essentials do it a moment after respawn
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) Util.console("warp " + warp + " " + p.getName());
        }, 3L);
    }
}
