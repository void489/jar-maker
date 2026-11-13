package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.util.Locale;

/**
 * Dying in a game mode respawns you at that mode's own warp instead of the lobby spawn.
 * Config (in-code default: prison -> starter):   respawn: { prison: starter }
 * Reads the Essentials warp file, so it uses the exact spot you set with /setwarp.
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        var p = e.getPlayer();
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
