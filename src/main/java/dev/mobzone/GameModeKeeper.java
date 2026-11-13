package dev.mobzone;

import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Players with the permission mobzone.keepgamemode remember their game mode PER WORLD.
 * Creative in the lobby, survival in prison, back to the lobby = creative again.
 * Give those players mv.bypass.gamemode.* too so Multiverse doesn't force the mode on entry.
 * First visit to a world uses the defaults (keep-gamemode.defaults, in code: lobby ADVENTURE, prison SURVIVAL).
 */
public final class GameModeKeeper implements Listener {
    private final MobZonePlugin plugin;
    private final Map<UUID, Map<String, String>> saved = new HashMap<>();
    private File file;

    public GameModeKeeper(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        file = new File(plugin.getDataFolder(), "gamemodes.yml");
        saved.clear();
        var y = YamlConfiguration.loadConfiguration(file);
        var root = y.getConfigurationSection("modes");
        if (root == null) return;
        for (String u : root.getKeys(false)) {
            try {
                var sec = root.getConfigurationSection(u);
                if (sec == null) continue;
                Map<String, String> m = new HashMap<>();
                for (String w : sec.getKeys(false)) m.put(w.toLowerCase(Locale.ROOT), sec.getString(w, ""));
                saved.put(UUID.fromString(u), m);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        var y = new YamlConfiguration();
        for (var e : saved.entrySet())
            for (var w : e.getValue().entrySet()) y.set("modes." + e.getKey() + "." + w.getKey(), w.getValue());
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save gamemodes.yml: " + ex.getMessage()); }
    }

    private boolean applies(Player p) {
        return plugin.getConfig().getBoolean("keep-gamemode.enabled", true) && p.hasPermission("mobzone.keepgamemode");
    }

    private GameMode defaultFor(String world) {
        String d = plugin.getConfig().getString("keep-gamemode.defaults." + world.toLowerCase(Locale.ROOT), null);
        if (d == null) d = world.equalsIgnoreCase("lobby") ? "ADVENTURE" : world.equalsIgnoreCase("prison") ? "SURVIVAL" : null;
        if (d == null) return null;
        try { return GameMode.valueOf(d.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    private void remember(Player p, String world, GameMode m) {
        saved.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(world.toLowerCase(Locale.ROOT), m.name());
        save();
    }

    /** Any change the player makes (or a command makes) is remembered for the world they are in. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        if (applies(e.getPlayer())) remember(e.getPlayer(), e.getPlayer().getWorld().getName(), e.getNewGameMode());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!applies(p)) return;
        // what they were in when they left the old world
        remember(p, e.getFrom().getName(), p.getGameMode());
        String to = p.getWorld().getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !p.getWorld().getName().equals(to)) return;
            String s = saved.getOrDefault(p.getUniqueId(), Map.of()).get(to.toLowerCase(Locale.ROOT));
            GameMode m = null;
            if (s != null) { try { m = GameMode.valueOf(s); } catch (IllegalArgumentException ignored) { } }
            if (m == null) m = defaultFor(to);
            if (m != null && p.getGameMode() != m) p.setGameMode(m);
        }, 3L);
    }
}
