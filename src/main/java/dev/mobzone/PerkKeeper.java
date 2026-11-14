package dev.mobzone;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/**
 * Armor perks (Dragon Heart = extra hearts, Speed) only work in the prison world.
 * They are removed when you leave it and given back, from the saved levels, when you come back.
 * Same values as the perks menu: Dragon Heart +5 health per level, Speed +20% per level.
 * Config (in-code default prison):   perks: { worlds: [prison] }
 */
public final class PerkKeeper implements Listener {
    private final MobZonePlugin plugin;

    public PerkKeeper(MobZonePlugin plugin) { this.plugin = plugin; }

    private boolean perkWorld(String w) {
        List<String> l = plugin.getConfig().getStringList("perks.worlds");
        if (l.isEmpty()) l = List.of("prison");
        for (String x : l) if (x.equalsIgnoreCase(w)) return true;
        return false;
    }

    private static int level(Player p, String meta) {
        String v = RankBonus.placeholder(p, "%luckperms_meta_" + meta + "%");
        if (v == null) return 0;
        try { return Math.max(0, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 0; }
    }

    private void remove(Player p) {
        String n = p.getName();
        Util.console("minecraft:attribute " + n + " minecraft:max_health modifier remove prison:dragon_heart");
        Util.console("minecraft:attribute " + n + " minecraft:movement_speed modifier remove prison:speed");
    }

    private void apply(Player p) {
        String n = p.getName();
        remove(p);
        int dh = level(p, "arm_dragon_heart"), sp = level(p, "arm_speed");
        if (dh > 0) Util.console("minecraft:attribute " + n + " minecraft:max_health modifier add prison:dragon_heart " + (5 * dh) + " add_value");
        if (sp > 0) Util.console("minecraft:attribute " + n + " minecraft:movement_speed modifier add prison:speed " + String.format(java.util.Locale.US, "%.1f", 0.2 * sp) + " add_multiplied_base");
    }

    private void sync(Player p) {
        if (!p.isOnline()) return;
        if (perkWorld(p.getWorld().getName())) apply(p); else remove(p);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(p), 5L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(p), 40L);
    }
}
