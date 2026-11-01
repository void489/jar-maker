package dev.mobzone;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

/** Infinite night vision (no particles, no HUD icon) for players in the configured worlds. */
public class NightVision {
    private final MobZonePlugin plugin;
    private BukkitTask task;
    private boolean enabled;
    private final Set<String> worlds = new HashSet<>();

    public NightVision(MobZonePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        if (task != null) { task.cancel(); task = null; }
        var c = plugin.getConfig();
        enabled = c.getBoolean("night-vision.enabled", true);
        worlds.clear();
        var list = c.getStringList("night-vision.worlds");
        if (list.isEmpty()) list = java.util.List.of("prison");
        for (String w : list) worlds.add(w.toLowerCase());
        if (!enabled) { for (Player p : plugin.getServer().getOnlinePlayers()) clear(p); return; }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 40L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (worlds.contains(p.getWorld().getName().toLowerCase())) apply(p);
            else clear(p);
        }
    }

    private void apply(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION && !cur.hasIcon()) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION,
                0, false, false, false));
    }

    /** Only removes OUR effect (infinite, no icon) so other night vision sources are untouched. */
    private void clear(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (cur != null && cur.getDuration() == PotionEffect.INFINITE_DURATION && !cur.hasIcon())
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }
}
