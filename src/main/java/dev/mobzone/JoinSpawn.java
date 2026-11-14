package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends players to the lobby warp a moment AFTER they have fully joined.
 * Essentials' own spawn-on-join teleports during login, which confuses Multiverse-Inventories
 * (you keep the prison inventory in the lobby). Turn spawn-on-join off in Essentials and use this.
 * Config (in-code defaults):  join-spawn: { enabled: true, warp: lobby, world: lobby }
 */
public final class JoinSpawn implements Listener {
    private final MobZonePlugin plugin;

    public JoinSpawn(MobZonePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var c = plugin.getConfig();
        if (!c.getBoolean("join-spawn.enabled", true)) return;
        String warp = c.getString("join-spawn.warp", "lobby");
        String world = c.getString("join-spawn.world", "lobby");
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.getWorld().getName().equalsIgnoreCase(world)) return;
            Util.console("warp " + warp + " " + p.getName());
        }, 20L);
    }
}
